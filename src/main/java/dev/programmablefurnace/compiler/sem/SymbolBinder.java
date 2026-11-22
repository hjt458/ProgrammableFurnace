package dev.programmablefurnace.compiler.sem;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.SourcePos;
import dev.programmablefurnace.compiler.syntax.Ast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 符号解析 / 绑定（链接期，草稿 §2.2 ③、§2.6 ④）：据存储表把声明的函数 / 变量绑定到
 * {@link NameMangler} 修饰出的实际名，并遍历代码解析引用——"据符号表找对实现"。
 * <p>内置依赖库清单（F6）未定稿，故凡本地解析不到的裸名 / 限定名一律按外部（内置库 / 导入模块）
 * 处理、不作错误；唯一硬错误为把本地变量当函数调用（{@link ErrorCode#T03_UNDECLARED_FUNCTION}）。
 * 同时收集实际被引用的本地符号名（{@link #referencedSymbols()}），供 §2.2 ④ import 最小单位优化使用。</p>
 */
public final class SymbolBinder {

    private final StorageTable table;
    private final Map<Symbol, String> funcManglings = new LinkedHashMap<>();
    private final Map<Symbol, String> varManglings = new LinkedHashMap<>();
    private final List<CompileError> errors = new ArrayList<>();
    private final Set<String> referencedSymbols = new LinkedHashSet<>();
    private final Set<String> referencedExternals = new LinkedHashSet<>();

    private SymbolBinder(StorageTable table) { this.table = table; }

    /** 对存储表 + AST 执行链接期绑定。 */
    public static SymbolBinder bind(StorageTable table, List<Ast.Decl> decls) {
        SymbolBinder b = new SymbolBinder(table);
        b.mangleDeclarations();
        b.collectReferences(decls);
        return b;
    }

    // ===================== 声明绑定 =====================

    private void mangleDeclarations() {
        String module = moduleName();
        for (Symbol s : table.allSymbols()) {
            switch (s.kind) {
                case FUNC -> funcManglings.put(s,
                        NameMangler.mangleFunc(module, s.scope, s.name, s.paramTypes));
                case VAR -> varManglings.put(s,
                        NameMangler.mangleVar(module, s.scope, s.name));
                default -> { }
            }
        }
    }

    private String moduleName() {
        String m = table.moduleName();
        return m == null ? "<unknown>" : m;
    }

    // ===================== 引用收集（遍历 AST） =====================

    private void collectReferences(List<Ast.Decl> decls) {
        for (Ast.Decl d : decls) walkDecl(d);
    }

    private void walkDecl(Ast.Decl d) {
        if (d instanceof Ast.FuncDecl f) {
            walkStmt(f.body);
        } else if (d instanceof Ast.TopLevelVarDecl tv) {
            walkExpr(tv.var.init);
        } else if (d instanceof Ast.NamespaceDecl ns) {
            for (Ast.Decl dd : ns.body) walkDecl(dd);
        }
        // MODULE / import / using 不含表达式引用
    }

    private void walkStmt(Ast.Stmt s) {
        if (s == null) return;
        if (s instanceof Ast.BlockStmt b) {
            for (Ast.Stmt st : b.stmts) walkStmt(st);
        } else if (s instanceof Ast.VarDeclStmt v) {
            walkExpr(v.init);
        } else if (s instanceof Ast.IfStmt i) {
            walkExpr(i.cond); walkStmt(i.thenBranch); walkStmt(i.elseBranch);
        } else if (s instanceof Ast.WhileStmt w) {
            walkExpr(w.cond); walkStmt(w.body);
        } else if (s instanceof Ast.DoWhileStmt dw) {
            walkStmt(dw.body); walkExpr(dw.cond);
        } else if (s instanceof Ast.ForStmt f) {
            walkStmt(f.init); walkExpr(f.cond); walkExpr(f.incr); walkStmt(f.body);
        } else if (s instanceof Ast.RangeForStmt r) {
            walkExpr(r.iterable); walkStmt(r.body);
        } else if (s instanceof Ast.SwitchStmt sw) {
            walkExpr(sw.subject);
            for (Ast.SwitchCase c : sw.cases) for (Ast.Stmt st : c.stmts) walkStmt(st);
        } else if (s instanceof Ast.ReturnStmt rt) {
            walkExpr(rt.value);
        } else if (s instanceof Ast.BreakStmt) {
            // 无引用
        } else if (s instanceof Ast.ContinueStmt) {
            // 无引用
        } else if (s instanceof Ast.ExprStmt e) {
            walkExpr(e.expr);
        }
    }

    private void walkExpr(Ast.Expr e) {
        if (e == null) return;
        if (e instanceof Ast.CallExpr c) {
            bindCall(c);
            walkExpr(c.callee);   // 使对象方法调用 obj.method 的 obj、限定名前缀也作为引用被遍历
            for (Ast.Expr a : c.args) walkExpr(a);
        } else if (e instanceof Ast.VarExpr v) {
            bindValueRef(v);
        } else if (e instanceof Ast.MemberExpr m) {
            if (!recordMemberRef(m)) walkExpr(m.obj); // 非限定前缀（如函数调用结果 obj）→ 照常遍历
        } else if (e instanceof Ast.BinaryExpr b) {
            walkExpr(b.left); walkExpr(b.right);
        } else if (e instanceof Ast.UnaryExpr u) {
            walkExpr(u.operand);
        } else if (e instanceof Ast.AssignExpr a) {
            walkExpr(a.lhs); walkExpr(a.rhs);
        } else if (e instanceof Ast.NewExpr n) {
            for (Ast.Expr a : n.args) walkExpr(a);
        } else if (e instanceof Ast.BraceListExpr bl) {
            for (Ast.Expr it : bl.items) walkExpr(it);
        } else if (e instanceof Ast.TernaryExpr t) {
            walkExpr(t.cond); walkExpr(t.thenBranch); walkExpr(t.elseBranch);
        }
        // LiteralExpr 无子引用
    }

    // ===================== 绑定规则 =====================

    private void bindCall(Ast.CallExpr c) {
        Ast.Expr callee = c.callee;
        if (callee instanceof Ast.VarExpr v) {
            Symbol s = table.resolve(v.name);
            if (s == null) { referencedExternals.add(v.name); return; }
            referencedSymbols.add(v.name);
            if (s.kind == Symbol.Kind.VAR) {
                errors.add(new CompileError(ErrorCode.T03_UNDECLARED_FUNCTION, c.pos,
                        "将变量 '" + v.name + "' 当函数调用"));
            }
        }
        // 限定调用（模块.成员 / ns::成员）由 walkExpr 的 recordMemberRef 记录；
        // 对象方法 obj.method（obj 为本地变量）→ 运行期方法派发，不在此绑定；
        // 类型构造（NewExpr）→ 运行期构造。
    }

    /**
     * 记录成员访问引用，区分三类——
     * <ul>
     *   <li>obj 为本地变量 → 对象（运行期派发），对象名记入 referencedSymbols；</li>
     *   <li>obj 为本地命名空间 → 命名空间成员，记入 referencedSymbols（前缀::成员）；</li>
     *   <li>obj 非本地（内置库 / 导入模块，如 {@code 模块.成员}、{@code 标签::粗铁}）→ 记入 referencedExternals。</li>
     * </ul>
     * 限定路径只记限定引用，不把裸限定符单列为值引用（避免污染 import 最小单位分析）。
     *
     * @return true 表示 obj 是限定路径（已处理，调用方无需再遍历）；false 表示 obj 非限定路径。
     */
    private boolean recordMemberRef(Ast.MemberExpr m) {
        String prefix = objPath(m.obj);
        if (prefix == null) return false;
        Symbol s = table.resolve(prefix);
        if (s == null) {
            referencedExternals.add(prefix + "::" + m.member);   // 导入模块 / 内置库成员
        } else if (s.kind == Symbol.Kind.NAMESPACE) {
            referencedSymbols.add(prefix + "::" + m.member);     // 本地命名空间成员
        } else {
            referencedSymbols.add(prefix);                       // 本地变量对象
        }
        return true;
    }

    private void bindValueRef(Ast.VarExpr v) {
        Symbol s = table.resolve(v.name);
        if (s == null) referencedExternals.add(v.name);
        else referencedSymbols.add(v.name);
    }

    private static String objPath(Ast.Expr obj) {
        if (obj == null) return null;
        if (obj instanceof Ast.VarExpr v) return v.name;
        if (obj instanceof Ast.MemberExpr m) {
            String p = objPath(m.obj);
            return p == null ? m.member : p + "::" + m.member;
        }
        return null;
    }

    // ===================== 结果 =====================

    /** 每个声明函数的修饰后实际名（键为存储表符号）。 */
    public Map<Symbol, String> funcManglings() { return funcManglings; }

    /** 每个声明变量的修饰后实际名。 */
    public Map<Symbol, String> varManglings() { return varManglings; }

    /** 实际被引用的本地符号名（供 §2.2 ④ import 最小单位优化）。 */
    public Set<String> referencedSymbols() { return referencedSymbols; }

    /** 本地解析不到的引用名（内置库 / 导入模块，信息性，不作错误）。 */
    public Set<String> referencedExternals() { return referencedExternals; }

    /** 链接期错误（当前仅"把变量当函数调用"），§4.6 编译期出错即标失败。 */
    public List<CompileError> errors() { return errors; }
}
