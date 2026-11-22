package dev.programmablefurnace.compiler.sem;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.SourcePos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储表（符号表，草稿 §2.4）。
 * <p>以文件（模块）为边界登记声明，不设跨文件全局作用域；作用域分 模块级 / 函数级 / 块级
 * + 自定义 {@code namespace} 分区（防同名）。每个符号条目 = {名字, 类型, 作用域, 附加属性}。
 * 跨文件引用只能经 {@code import} 显式引入（记入 {@link #imports()}）。</p>
 * <p>同作用域重名符号按 §4.6 记为编译错误（变量 {@code T05}，函数 {@code T04}，模块 {@code M02}）。</p>
 */
public final class StorageTable {

    /** 作用域层级（草稿 §2.4：模块级 / 函数级 / 块级 + namespace 分区）。 */
    enum ScopeKind { MODULE, NAMESPACE, FUNCTION, BLOCK }

    private String moduleName;
    private final Scope root;
    private Scope cur;
    private final List<CompileError> errors = new ArrayList<>();
    private final List<ImportEntry> imports = new ArrayList<>();
    private final List<UsingRef> usingRefs = new ArrayList<>();

    public StorageTable() {
        root = new Scope(ScopeKind.MODULE, "<module>", null);
        cur = root;
    }

    // ===================== 模块 =====================

    public void setModuleName(String n) { this.moduleName = n; }
    public String moduleName() { return moduleName; }

    // ===================== 作用域管理 =====================

    /** 进入子作用域；kind 为 NAMESPACE 时在父作用域登记对应 namespace 分区（供限定名解析）。 */
    public void pushScope(ScopeKind kind, String name) {
        Scope s = new Scope(kind, name, cur);
        cur = s;
        if (s.parent != null) {
            s.parent.children.add(s);
            if (kind == ScopeKind.NAMESPACE) s.parent.namespaces.put(name, s);
        }
    }

    /** 退出到父作用域（根作用域不可再退）。 */
    public void popScope() { if (cur.parent != null) cur = cur.parent; }

    /** 当前作用域的 namespace 路径（模块根为 {@code <module>}）。 */
    public String currentScopePath() {
        List<String> parts = new ArrayList<>();
        for (Scope s = cur; s != null; s = s.parent) {
            if (s.kind == ScopeKind.NAMESPACE) parts.add(s.name);
        }
        if (parts.isEmpty()) return "<module>";
        StringBuilder sb = new StringBuilder();
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append("::");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ===================== 符号登记（防同名） =====================

    /** 在当前作用域登记符号；同名函数且参数不同 → 重载合法登记，否则按 §4.6 报冲突。 */
    public void addSymbol(Symbol s) {
        Symbol prev = cur.symbols.get(s.name);
        if (prev != null) {
            // 同名函数但形参不同 → 重载，登记到 overloads（mangling 按参数区分）
            if (isOverload(prev, s)) {
                cur.overloads.computeIfAbsent(s.name, k -> new ArrayList<>()).add(s);
                return;
            }
            boolean func = s.kind == Symbol.Kind.FUNC || prev.kind == Symbol.Kind.FUNC;
            addError(func ? ErrorCode.T04_OVERLOAD_CONFLICT : ErrorCode.T05_VARIABLE_REDEFINITION,
                    s.pos, "符号 '" + s.name + "' 在当前作用域重复定义");
            return;
        }
        cur.symbols.put(s.name, s);
    }

    /** 是否为函数重载：两者皆函数且形参类型表不同。 */
    private static boolean isOverload(Symbol a, Symbol b) {
        return a.kind == Symbol.Kind.FUNC && b.kind == Symbol.Kind.FUNC
                && !java.util.Objects.equals(a.paramTypes, b.paramTypes);
    }

    // ===================== 名称解析（草稿 §2.2 ③ 的存储表基础） =====================

    /** 词法作用域解析：从当前作用域向上查（块/函数局部遮蔽外层）；支持 {@code ns::name} 全限定。 */
    public Symbol resolve(String name) {
        if (name.contains("::")) return resolveQualified(name);
        for (Scope s = cur; s != null; s = s.parent) {
            Symbol sym = s.symbols.get(name);
            if (sym != null) return sym;
        }
        return null;
    }

    /** 全限定名解析：从模块根沿 namespace 路径定位。 */
    public Symbol resolveQualified(String qname) {
        String[] parts = qname.split("::");
        Scope s = root;
        for (int i = 0; i + 1 < parts.length; i++) {
            s = s.namespaces.get(parts[i]);
            if (s == null) return null;
        }
        return s == null ? null : s.symbols.get(parts[parts.length - 1]);
    }

    // ===================== 查询 =====================

    /** 模块级（顶层）符号，不含嵌套 namespace 内部（含重载）。 */
    public List<Symbol> topLevelSymbols() {
        List<Symbol> out = new ArrayList<>(root.symbols.values());
        for (List<Symbol> l : root.overloads.values()) out.addAll(l);
        return out;
    }

    /** 全部符号（含嵌套 namespace、函数级/块级局部符号，含重载）。 */
    public List<Symbol> allSymbols() {
        List<Symbol> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Scope s, List<Symbol> out) {
        out.addAll(s.symbols.values());
        for (List<Symbol> l : s.overloads.values()) out.addAll(l);
        for (Scope c : s.children) collect(c, out);
    }

    // ===================== 导入 / using（草稿 §2.4、§3.5） =====================

    public void addImport(ImportEntry e) { imports.add(e); }
    /** 本文件显式 import 的模块 / 符号（跨文件引用的唯一入口）。 */
    public List<ImportEntry> imports() { return imports; }

    public void addUsingRef(String ns, String member, SourcePos pos) { usingRefs.add(new UsingRef(ns, member, pos)); }
    /** using 命名空间::成员 引入（草稿 §3.2 形式 2）。 */
    public List<UsingRef> usingRefs() { return usingRefs; }

    // ===================== 错误 =====================

    public void addError(ErrorCode code, SourcePos pos, String msg) {
        errors.add(new CompileError(code, pos, msg));
    }

    /** 存储表构建期错误（防同名 / 模块重复等），§4.6 编译期出错即标失败。 */
    public List<CompileError> errors() { return errors; }

    // ===================== 内部结构 =====================

    private static final class Scope {
        final ScopeKind kind;
        final String name;
        final Scope parent;
        final Map<String, Symbol> symbols = new LinkedHashMap<>();
        final Map<String, List<Symbol>> overloads = new LinkedHashMap<>();
        final Map<String, Scope> namespaces = new LinkedHashMap<>();
        final List<Scope> children = new ArrayList<>();
        Scope(ScopeKind kind, String name, Scope parent) {
            this.kind = kind; this.name = name; this.parent = parent;
        }
    }

    /** 导入记录：{@code import module;}（wholeModule=true）或 {@code from module import symbol;}。 */
    public record ImportEntry(String module, String symbol, boolean wholeModule, SourcePos pos) {}

    /** {@code using namespace::member;} 单成员引入。 */
    public record UsingRef(String ns, String member, SourcePos pos) {}
}
