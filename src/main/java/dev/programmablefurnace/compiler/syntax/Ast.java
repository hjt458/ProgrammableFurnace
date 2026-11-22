package dev.programmablefurnace.compiler.syntax;

import dev.programmablefurnace.compiler.lex.SourcePos;
import dev.programmablefurnace.compiler.lex.Token;
import java.util.List;

/**
 * 语法树（AST）节点。所有语法节点均为 {@link Ast.Node} 的静态嵌套类，由 {@link Parser} 产出。
 * <p>覆盖草稿 §1（MODULE）、§3（词法/语法要素：变量/控制流/函数/import/using/namespace/表达式）。
 * 节点携带 {@link SourcePos} 供编译错误定位。</p>
 */
public final class Ast {

    private Ast() {}

    /** 节点基类。 */
    public abstract static class Node {
        public final SourcePos pos;
        protected Node(SourcePos pos) { this.pos = pos; }
    }

    /** 顶层声明节点基类（模块声明、import、using、namespace、函数、顶层变量）。 */
    public abstract static class Decl extends Node {
        protected Decl(SourcePos pos) { super(pos); }
    }

    /** 语句节点基类。 */
    public abstract static class Stmt extends Node {
        protected Stmt(SourcePos pos) { super(pos); }
    }

    /** 表达式节点基类。 */
    public abstract static class Expr extends Node {
        protected Expr(SourcePos pos) { super(pos); }
    }

    // ===================== 类型 =====================

    /** 类型节点：基础类型名、泛型类型（vector&lt;T&gt;）、auto。 */
    public static final class TypeNode extends Node {
        public final String name;          // 如 "int" / "vector" / "auto"
        public final List<TypeNode> args;  // 泛型参数，如 vector 的 <Container>；无则空
        public TypeNode(SourcePos pos, String name, List<TypeNode> args) {
            super(pos);
            this.name = name;
            this.args = args;
        }
        @Override public String toString() {
            if (args.isEmpty()) return name;
            StringBuilder sb = new StringBuilder(name).append('<');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(args.get(i));
            }
            return sb.append('>').toString();
        }
    }

    // ===================== 顶层声明 =====================

    /** MODULE("name")：文件第一行的模块声明（草稿 §1.1）。 */
    public static final class ModuleDecl extends Decl {
        public final String name;
        public ModuleDecl(SourcePos pos, String name) { super(pos); this.name = name; }
    }

    /** import 模块名 —— 全量导入（草稿 §3.5）。 */
    public static final class ImportDecl extends Decl {
        public final String module;
        public ImportDecl(SourcePos pos, String module) { super(pos); this.module = module; }
    }

    /** from 模块名 import 符号（函数/命名空间）—— 精确导入（草稿 §3.5）。 */
    public static final class FromImportDecl extends Decl {
        public final String module;
        public final String symbol;
        public FromImportDecl(SourcePos pos, String module, String symbol) {
            super(pos); this.module = module; this.symbol = symbol;
        }
    }

    /** using 命名空间::成员; 或 using 新名 = 类型;（草稿 §3.2 的 2/3）。 */
    public static final class UsingDecl extends Decl {
        public final String ns;       // 命名空间（using ns::member）或 null（using 别名）
        public final String member;   // 成员名
        public final String alias;    // 类型别名新名（using 别名）或 null
        public final TypeNode aliasType; // 别名指向的类型（using 别名）或 null
        public UsingDecl(SourcePos pos, String ns, String member, String alias, TypeNode aliasType) {
            super(pos);
            this.ns = ns; this.member = member; this.alias = alias; this.aliasType = aliasType;
        }
    }

    /** namespace 命名空间名 { ... } —— 自定义命名空间（草稿 §3.2）。 */
    public static final class NamespaceDecl extends Decl {
        public final String name;
        public final List<Decl> body;
        public NamespaceDecl(SourcePos pos, String name, List<Decl> body) {
            super(pos); this.name = name; this.body = body;
        }
    }

    /** 函数声明（草稿 §3.4）：返回类型、名称、形参表、函数体。RunLoop 亦为此节点（isExec）。 */
    public static final class FuncDecl extends Decl {
        public final TypeNode returnType;     // 可为 void
        public final String name;
        public final List<Param> params;
        public final BlockStmt body;
        public final boolean isRunLoop;       // 是否为执行函数 RunLoop
        public FuncDecl(SourcePos pos, TypeNode returnType, String name, List<Param> params,
                        BlockStmt body, boolean isRunLoop) {
            super(pos);
            this.returnType = returnType; this.name = name; this.params = params;
            this.body = body; this.isRunLoop = isRunLoop;
        }
    }

    /** 形参：类型 + 名称。 */
    public static final class Param extends Node {
        public final TypeNode type;
        public final String name;
        public Param(SourcePos pos, TypeNode type, String name) {
            super(pos); this.type = type; this.name = name;
        }
    }

    /** 顶层变量声明（模块级，草稿 §2 存储表登记）。 */
    public static final class VarDeclStmt extends Stmt {
        public final TypeNode type;
        public final String name;
        public final Expr init;  // 可空（取类型默认值）
        public VarDeclStmt(SourcePos pos, TypeNode type, String name, Expr init) {
            super(pos); this.type = type; this.name = name; this.init = init;
        }
    }

    /** 顶层变量声明（Decl 包装，含一个 VarDeclStmt；用于模块级作用域登记）。 */
    public static final class TopLevelVarDecl extends Decl {
        public final VarDeclStmt var;
        public TopLevelVarDecl(SourcePos pos, VarDeclStmt var) { super(pos); this.var = var; }
    }

    // ===================== 语句 =====================

    /** 块语句 { ... }。 */
    public static final class BlockStmt extends Stmt {
        public final List<Stmt> stmts;
        public BlockStmt(SourcePos pos, List<Stmt> stmts) { super(pos); this.stmts = stmts; }
    }

    /** if / else if / else。 */
    public static final class IfStmt extends Stmt {
        public final Expr cond;
        public final Stmt thenBranch;
        public final Stmt elseBranch;  // 可空
        public IfStmt(SourcePos pos, Expr cond, Stmt thenBranch, Stmt elseBranch) {
            super(pos); this.cond = cond; this.thenBranch = thenBranch; this.elseBranch = elseBranch;
        }
    }

    /** while (cond) stmt。 */
    public static final class WhileStmt extends Stmt {
        public final Expr cond;
        public final Stmt body;
        public WhileStmt(SourcePos pos, Expr cond, Stmt body) {
            super(pos); this.cond = cond; this.body = body;
        }
    }

    /** do stmt while (cond);。 */
    public static final class DoWhileStmt extends Stmt {
        public final Stmt body;
        public final Expr cond;
        public DoWhileStmt(SourcePos pos, Stmt body, Expr cond) {
            super(pos); this.body = body; this.cond = cond;
        }
    }

    /** for (init; cond; incr) stmt。 */
    public static final class ForStmt extends Stmt {
        public final Stmt init;  // 可为声明或表达式语句，可空
        public final Expr cond;  // 可空
        public final Expr incr;  // 可空
        public final Stmt body;
        public ForStmt(SourcePos pos, Stmt init, Expr cond, Expr incr, Stmt body) {
            super(pos); this.init = init; this.cond = cond; this.incr = incr; this.body = body;
        }
    }

    /** 范围 for（for (类型 x : 容器) stmt，草稿 §3.3）。 */
    public static final class RangeForStmt extends Stmt {
        public final TypeNode elemType;
        public final String var;
        public final Expr iterable;
        public final Stmt body;
        public RangeForStmt(SourcePos pos, TypeNode elemType, String var, Expr iterable, Stmt body) {
            super(pos); this.elemType = elemType; this.var = var;
            this.iterable = iterable; this.body = body;
        }
    }

    /** switch (expr) { case ... }。 */
    public static final class SwitchStmt extends Stmt {
        public final Expr subject;
        public final List<SwitchCase> cases;
        public SwitchStmt(SourcePos pos, Expr subject, List<SwitchCase> cases) {
            super(pos); this.subject = subject; this.cases = cases;
        }
    }

    /** switch 的一个 case 分支。 */
    public static final class SwitchCase extends Node {
        public final Expr value;  // null 表示 default
        public final List<Stmt> stmts;
        public SwitchCase(SourcePos pos, Expr value, List<Stmt> stmts) {
            super(pos); this.value = value; this.stmts = stmts;
        }
    }

    /** return [expr]; */
    public static final class ReturnStmt extends Stmt {
        public final Expr value;  // 可空（void）
        public ReturnStmt(SourcePos pos, Expr value) { super(pos); this.value = value; }
    }

    /** break; 跳出最近的 switch / 循环。 */
    public static final class BreakStmt extends Stmt {
        public BreakStmt(SourcePos pos) { super(pos); }
    }

    /** continue; 跳到最近循环的下一轮迭代（不跳出循环）。 */
    public static final class ContinueStmt extends Stmt {
        public ContinueStmt(SourcePos pos) { super(pos); }
    }

    /** 表达式语句（如函数调用、赋值）。 */
    public static final class ExprStmt extends Stmt {
        public final Expr expr;
        public ExprStmt(SourcePos pos, Expr expr) { super(pos); this.expr = expr; }
    }

    // ===================== 表达式 =====================

    /** 字面量：数字/字符串/布尔。 */
    public static final class LiteralExpr extends Expr {
        public final TokenTypeKind kind;
        public final String value;
        public LiteralExpr(SourcePos pos, TokenTypeKind kind, String value) {
            super(pos); this.kind = kind; this.value = value;
        }
        public enum TokenTypeKind { INTEGER, FLOAT, STRING, BOOL }
    }

    /** 变量引用。 */
    public static final class VarExpr extends Expr {
        public final String name;
        public VarExpr(SourcePos pos, String name) { super(pos); this.name = name; }
    }

    /** 二元运算（含短路 && / ||）。 */
    public static final class BinaryExpr extends Expr {
        public final Expr left;
        public final Token op;
        public final Expr right;
        public BinaryExpr(SourcePos pos, Expr left, Token op, Expr right) {
            super(pos); this.left = left; this.op = op; this.right = right;
        }
    }

    /** 一元运算（!、-）。 */
    public static final class UnaryExpr extends Expr {
        public final Token op;
        public final Expr operand;
        public UnaryExpr(SourcePos pos, Token op, Expr operand) {
            super(pos); this.op = op; this.operand = operand;
        }
    }

    /** 赋值：lhs = rhs（lhs 通常为 VarExpr 或 MemberExpr）。 */
    public static final class AssignExpr extends Expr {
        public final Expr lhs;
        public final Expr rhs;
        public AssignExpr(SourcePos pos, Expr lhs, Expr rhs) {
            super(pos); this.lhs = lhs; this.rhs = rhs;
        }
    }

    /** 函数调用：callee(args)。callee 可为 VarExpr/MemberExpr（对象.方法）。 */
    public static final class CallExpr extends Expr {
        public final Expr callee;
        public final List<Expr> args;
        public CallExpr(SourcePos pos, Expr callee, List<Expr> args) {
            super(pos); this.callee = callee; this.args = args;
        }
    }

    /** 成员访问：obj.member（含命名空间限定 ns::member 与对象属性 obj.field）。 */
    public static final class MemberExpr extends Expr {
        public final Expr obj;     // 可空（如 :: 开头的全局）
        public final String member;
        public final boolean isNamespace; // true 表示 :: 命名空间限定，false 表示 . 成员访问
        public MemberExpr(SourcePos pos, Expr obj, String member, boolean isNamespace) {
            super(pos); this.obj = obj; this.member = member; this.isNamespace = isNamespace;
        }
    }

    /** 对象构造 / 初始化列表：vector{...} / pair(...) 等。 */
    public static final class NewExpr extends Expr {
        public final TypeNode type;
        public final List<Expr> args;
        public final boolean braced;  // true 用 { }，false 用 ( )
        public NewExpr(SourcePos pos, TypeNode type, List<Expr> args, boolean braced) {
            super(pos); this.type = type; this.args = args; this.braced = braced;
        }
    }

    /** 花括号初始化列表 { expr, ... }（裸列表，无类型前缀）：可作函数实参、vector 初始化等。 */
    public static final class BraceListExpr extends Expr {
        public final List<Expr> items;
        public BraceListExpr(SourcePos pos, List<Expr> items) {
            super(pos); this.items = items;
        }
    }

    /** 下标访问：base[index]（vector 取元素，草稿 §4.10.2 槽组模型）。 */
    public static final class SubscriptExpr extends Expr {
        public final Expr base;
        public final Expr index;
        public SubscriptExpr(SourcePos pos, Expr base, Expr index) {
            super(pos); this.base = base; this.index = index;
        }
    }

    /** 条件表达式 cond ? a : b。 */
    public static final class TernaryExpr extends Expr {
        public final Expr cond;
        public final Expr thenBranch;
        public final Expr elseBranch;
        public TernaryExpr(SourcePos pos, Expr cond, Expr thenBranch, Expr elseBranch) {
            super(pos); this.cond = cond; this.thenBranch = thenBranch; this.elseBranch = elseBranch;
        }
    }
}
