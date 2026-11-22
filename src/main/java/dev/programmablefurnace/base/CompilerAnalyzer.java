package dev.programmablefurnace.base;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.SourcePos;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.sem.ImportOptimizer;
import dev.programmablefurnace.compiler.sem.ImportResolver;
import dev.programmablefurnace.compiler.sem.ModuleRegistry;
import dev.programmablefurnace.compiler.sem.StorageBuilder;
import dev.programmablefurnace.compiler.sem.StorageTable;
import dev.programmablefurnace.compiler.sem.Symbol;
import dev.programmablefurnace.compiler.sem.SymbolBinder;
import dev.programmablefurnace.compiler.sem.TypeChecker;
import dev.programmablefurnace.compiler.syntax.Ast.Decl;
import dev.programmablefurnace.compiler.syntax.Ast.FuncDecl;
import dev.programmablefurnace.compiler.syntax.Ast.NamespaceDecl;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实代码分析器：用词法分析（{@link Lexer}）+ 语法分析（{@link Parser}）+ 存储表（{@link StorageTable}）
 * + 符号绑定（{@link SymbolBinder}）判定源代码的内容状态，替代占位的 {@link PlaceholderCodeAnalyzer}。
 * 判定规则（草稿 §4.5 / §4.6）：
 * <ul>
 *   <li>空 / 纯空白 → {@link ContentState#BLANK}</li>
 *   <li>词法、语法、存储表或链接期绑定出错 → {@link ContentState#FAILED}（编译期出错即标失败）</li>
 *   <li>无错误且含执行函数 {@code RunLoop} → {@link ContentState#EXECUTABLE}（可执行文件）</li>
 *   <li>无错误且不含 {@code RunLoop} → {@link ContentState#DEPENDENCY}（依赖库文件）</li>
 * </ul>
 * 纯 JVM 实现，无 MC 依赖，可在 classpath 无 MC 时直接单测。
 */
public final class CompilerAnalyzer implements CodeAnalyzer {

    /** classify 阶段无文件名上下文，用占位名作错误定位（正常编译会走带文件名的完整流程）。 */
    private static final String CLASSIFY_NAME = "<classify>";

    @Override
    public ContentState analyze(String source) {
        return compile(source).state();
    }

    /**
     * 完整编译单文件并返回结果（三态 + AST 声明 + 存储表），供运行器复用。
     * <p>{@code decls}/{@code table} 在失败时可能为空或部分，仅当 {@code state == EXECUTABLE} 时
     * 可安全交给 {@code Executor.of(decls, table, env)} 执行。</p>
     */
    public static CompileResult compile(String source) {
        return compile(CLASSIFY_NAME, source);
    }

    /** 带文件名的单文件编译：错误定位用真实文件名，便于玩家在依赖库里对上号。 */
    public static CompileResult compile(String fileName, String source) {
        try {
            return compileUnchecked(fileName, source);
        } catch (Parser.ParseDepthExceeded ex) {
            return failed(ex.getMessage());
        } catch (StackOverflowError ex) {
            // 递归下降遇到畸形嵌套会压穿 Java 栈；Error 一旦冒到服务端 ticker 就是崩服，必须在这里收口。
            return failed("源码嵌套过深，超出解析器安全范围");
        } catch (RuntimeException ex) {
            return failed("编译期内部错误: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    /**
     * 链接期编译：把一整个依赖库（多个文件）当成模块集合，先各自编译，再按 {@code MODULE} 名互相 linkage。
     *
     * <p>草稿 §2.2 / §4.9 的"链接"在这里落地：每个模块独立走词法→语法→存储表→绑定→类型检查，
     * 然后填 {@link ModuleRegistry}，由 {@link ImportResolver} 逐模块校验 import（M03 模块不存在 /
     * M04 符号不存在 / M05 循环导入），最后把被导入模块的声明与入口模块的声明合并成一份
     * {@code decls} 交给执行器（执行器按裸名找函数，所以跨模块调用能直接命中）。</p>
     *
     * <p>入口模块 = 第一个状态为 {@link ContentState#EXECUTABLE}（含 RunLoop）的模块；没有入口时
     * 返回 {@link ContentState#DEPENDENCY}，交给调用方决定"这批文件只是依赖库"。</p>
     */
    public static ModulesResult compileModules(List<ModuleSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return new ModulesResult(ContentState.BLANK, null, List.of(), null, List.of(), List.of());
        }
        // 第一遍：各自走"词法→语法→存储表→绑定"（不依赖别的模块）
        List<Parsed> parsed = new ArrayList<>();
        for (ModuleSource src : sources) {
            parsed.add(src.code() == null || src.code().isBlank()
                    ? new Parsed(src.name(), List.of(), null, null, List.of())
                    : parseAndBind(src.name(), src.code()));
        }
        // 第二遍：登记模块表，再把被导入的符号注入各自的存储表（类型检查靠它认得跨模块调用）
        ModuleRegistry registry = new ModuleRegistry();
        for (Parsed p : parsed) {
            if (p.table() != null && p.errors().isEmpty()) {
                registry.register(moduleNameOf(p), p.table());
            }
        }
        for (Parsed p : parsed) {
            if (p.table() == null || p.binder() == null || p.errors().size() > 0) continue;
            injectImportedSymbols(p, registry);
        }
        // 第三遍：类型检查 + 入口契约 + 三态，顺带做链接期 M03/M04/M05 校验
        List<ModuleResult> modules = new ArrayList<>();
        List<CompileError> allErrors = new ArrayList<>();
        for (Parsed p : parsed) {
            CompileResult r = finishModule(p);
            String moduleName = moduleNameOf(p);
            modules.add(new ModuleResult(moduleName, r.state(), r.decls(), r.table(), r.errors()));
            allErrors.addAll(r.errors());
            if (r.table() != null && r.state() != ContentState.FAILED && !r.table().imports().isEmpty()) {
                SymbolBinder binder = SymbolBinder.bind(r.table(), r.decls());
                ImportOptimizer opt = ImportOptimizer.optimize(r.table(), binder);
                allErrors.addAll(ImportResolver.resolve(r.table(), opt, registry).errors());
            }
        }
        ModuleResult entry = null;
        for (ModuleResult m : modules) {
            if (m.state() == ContentState.EXECUTABLE) { entry = m; break; }
        }
        if (!allErrors.isEmpty()) {
            return new ModulesResult(ContentState.FAILED, entry == null ? null : entry.name(),
                    entry == null ? List.of() : entry.decls(),
                    entry == null ? null : entry.table(), allErrors, modules);
        }
        if (entry == null) {
            return new ModulesResult(ContentState.DEPENDENCY, null, List.of(), null, List.of(), modules);
        }
        // 依赖在前、入口在后：执行器按裸名登记函数，后写覆盖先写，冲突时入口自己的定义生效
        List<Decl> merged = new ArrayList<>();
        for (ModuleResult m : modules) {
            if (m == entry || m.state() != ContentState.DEPENDENCY) continue;
            merged.addAll(m.decls());
        }
        merged.addAll(entry.decls());
        return new ModulesResult(ContentState.EXECUTABLE, entry.name(), merged, entry.table(),
                List.of(), modules);
    }

    /**
     * 把 import 实际用到的符号从目标模块的存储表拷进当前模块——这就是"链接"。
     * <p>不拷贝未使用的 import（草稿 §2.2④：import 以最小单位登记）；目标模块缺失或符号不存在时
     * 不在这里报错，交给 {@link ImportResolver} 统一报 M03/M04（避免同一问题报两遍）。</p>
     */
    private static void injectImportedSymbols(Parsed p, ModuleRegistry registry) {
        ImportOptimizer opt = ImportOptimizer.optimize(p.table(), p.binder());
        for (ImportOptimizer.ImportUse use : opt.usedImports()) {
            StorageTable target = registry.resolve(use.module());
            if (target == null) continue;
            for (String ref : use.refs()) {
                String simple = ref.contains("::") ? ref.substring(ref.indexOf("::") + 2) : ref;
                Symbol sym = target.resolve(simple);
                if (sym != null) p.table().addSymbol(sym);
            }
        }
    }

    /** 模块名取 {@code MODULE("…")} 登记的名字；没写就退回文件名。 */
    private static String moduleNameOf(Parsed p) {
        if (p.table() != null && p.table().moduleName() != null && !p.table().moduleName().isBlank()) {
            return p.table().moduleName();
        }
        return p.fileName();
    }

    /** 一个源文件：文件名（用于错误定位）+ 代码。 */
    public record ModuleSource(String name, String code) {}

    /** 单个模块的编译产物。 */
    public record ModuleResult(String name, ContentState state, List<Decl> decls,
                               StorageTable table, List<CompileError> errors) {}

    /** 链接期结果：整体状态 + 入口模块名 + 合并后的声明（供执行器）+ 入口存储表 + 错误 + 每模块明细。 */
    public record ModulesResult(ContentState state, String entryModule, List<Decl> decls,
                                StorageTable table, List<CompileError> errors,
                                List<ModuleResult> modules) {
        public ModulesResult {
            decls = decls == null ? List.of() : List.copyOf(decls);
            errors = errors == null ? List.of() : List.copyOf(errors);
            modules = modules == null ? List.of() : List.copyOf(modules);
        }

        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    /** 异常路径统一转 FAILED：错误写进存储表（随 table 交给上层），绝不把异常抛给调用方。 */
    private static CompileResult failed(String message) {
        StorageTable table = new StorageTable();
        table.addError(ErrorCode.S01_BAD_STATEMENT,
                new SourcePos(CLASSIFY_NAME, 1, 1),
                message == null ? "编译期未知错误" : message);
        return new CompileResult(ContentState.FAILED, List.of(), table, table.errors());
    }

    /** 前四个阶段（词法→语法→存储表→绑定）的产物；类型检查放到链接之后，因为它要看得到导入的符号。 */
    private record Parsed(String fileName, List<Decl> decls, StorageTable table, SymbolBinder binder,
                          List<CompileError> errors) {}

    /** 词法→语法→存储表→绑定：这一半不依赖其它模块。 */
    private static Parsed parseAndBind(String fileName, String source) {
        Lexer lexer = new Lexer(fileName, source);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens);
        List<Decl> decls = parser.parseFile();
        if (!lexer.errors().isEmpty() || !parser.errors().isEmpty()) {
            List<CompileError> errors = new ArrayList<>(lexer.errors());
            errors.addAll(parser.errors());
            return new Parsed(fileName, decls, null, null, errors);
        }
        StorageTable table = StorageBuilder.build(decls);
        if (!table.errors().isEmpty()) {
            return new Parsed(fileName, decls, table, null, table.errors());
        }
        SymbolBinder binder = SymbolBinder.bind(table, decls);
        if (!binder.errors().isEmpty()) {
            return new Parsed(fileName, decls, table, binder, binder.errors());
        }
        return new Parsed(fileName, decls, table, binder, List.of());
    }

    /** 后半段：类型检查 + 入口契约 + 定三态。 */
    private static CompileResult finishModule(Parsed p) {
        if (!p.errors().isEmpty()) {
            return new CompileResult(ContentState.FAILED, p.decls(), p.table(), p.errors());
        }
        TypeChecker checker = TypeChecker.check(p.table(), p.decls());
        if (!checker.errors().isEmpty()) {
            return new CompileResult(ContentState.FAILED, p.decls(), p.table(), checker.errors());
        }

        // 入口契约：RunLoop 必须返回 void——入口的返回值会被直接丢弃，非 void 只会误导作者。
        // 这类文件即使语法正确也"不可作为程序运行"，判失败并报 M06，而不是贴个 EXECUTABLE 标签骗人。
        List<CompileError> entryErrors = new ArrayList<>();
        collectEntryErrors(p.decls(), entryErrors);
        if (!entryErrors.isEmpty()) {
            return new CompileResult(ContentState.FAILED, p.decls(), p.table(), entryErrors);
        }

        // 文件类型由是否含执行函数 RunLoop 决定（草稿 §4.5）
        boolean hasRunLoop = p.decls().stream().anyMatch(CompilerAnalyzer::containsRunLoop);
        return new CompileResult(
                hasRunLoop ? ContentState.EXECUTABLE : ContentState.DEPENDENCY,
                p.decls(), p.table(), List.of());
    }

    private static CompileResult compileUnchecked(String fileName, String source) {
        if (source == null || source.isBlank()) {
            return new CompileResult(ContentState.BLANK, List.of(), null, List.of());
        }
        return finishModule(parseAndBind(fileName, source));
    }

    /**
     * 编译结果：三态 + AST 声明 + 存储表 + 编译错误列表。
     * <p>{@code errors} 在成功与 BLANK 时为空；失败时按阶段顺序（词法→语法→存储表→绑定→类型）
     * 带上该阶段产出的全部 {@link CompileError}，供编译器写进物品、界面展示与日志。</p>
     */
    public record CompileResult(ContentState state, List<Decl> decls, StorageTable table,
                                List<CompileError> errors) {
        public CompileResult {
            decls = decls == null ? List.of() : List.copyOf(decls);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** 是否有编译错误（等价于 {@code state == FAILED} 且错误列表非空）。 */
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** 收集执行入口签名问题（含嵌套 namespace 里的 RunLoop）。 */
    private static void collectEntryErrors(List<Decl> decls, List<CompileError> out) {
        for (Decl d : decls) {
            if (d instanceof FuncDecl f) {
                if (!f.isRunLoop) continue;
                // 入口的返回值会被 Executor 直接丢弃，声明成非 void 只会误导作者（批次 3 记录）。
                String rt = f.returnType == null ? "void" : f.returnType.toString();
                if (!"void".equals(rt)) {
                    out.add(new CompileError(ErrorCode.M06_MISSING_EXEC_ENTRY, f.pos,
                            "执行入口 RunLoop 必须返回 void（当前为 '" + rt + "'），返回值不会有人接收"));
                }
            } else if (d instanceof NamespaceDecl ns) {
                collectEntryErrors(ns.body, out);
            }
        }
    }

    /** 判断顶层声明（含嵌套 namespace）中是否含执行函数 RunLoop。 */
    private static boolean containsRunLoop(Decl d) {
        if (d instanceof FuncDecl f) return f.isRunLoop;
        if (d instanceof NamespaceDecl ns) {
            return ns.body.stream().anyMatch(CompilerAnalyzer::containsRunLoop);
        }
        return false;
    }
}
