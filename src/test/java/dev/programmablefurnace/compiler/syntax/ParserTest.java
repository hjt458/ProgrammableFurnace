package dev.programmablefurnace.compiler.syntax;

import static org.assertj.core.api.Assertions.assertThat;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.lex.TokenType;
import dev.programmablefurnace.compiler.syntax.Ast.BinaryExpr;
import dev.programmablefurnace.compiler.syntax.Ast.BlockStmt;
import dev.programmablefurnace.compiler.syntax.Ast.Decl;
import dev.programmablefurnace.compiler.syntax.Ast.ExprStmt;
import dev.programmablefurnace.compiler.syntax.Ast.ForStmt;
import dev.programmablefurnace.compiler.syntax.Ast.FuncDecl;
import dev.programmablefurnace.compiler.syntax.Ast.IfStmt;
import dev.programmablefurnace.compiler.syntax.Ast.LiteralExpr;
import dev.programmablefurnace.compiler.syntax.Ast.ModuleDecl;
import dev.programmablefurnace.compiler.syntax.Ast.RangeForStmt;
import dev.programmablefurnace.compiler.syntax.Ast.ReturnStmt;
import dev.programmablefurnace.compiler.syntax.Ast.VarDeclStmt;
import dev.programmablefurnace.compiler.syntax.Ast.VarExpr;
import dev.programmablefurnace.compiler.syntax.Ast.WhileStmt;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Parser} 语法分析器单元测试。
 * 覆盖：MODULE、import/from/using/namespace、变量/函数声明、控制流、表达式优先级、错误码。
 */
class ParserTest {

    private List<Decl> parse(String src) {
        List<Token> toks = new Lexer("test.mc", src).tokenize();
        return new Parser(toks).parseFile();
    }

    private List<CompileError> errors(String src) {
        List<Token> toks = new Lexer("test.mc", src).tokenize();
        Parser p = new Parser(toks);
        p.parseFile();
        return p.errors();
    }

    // ===================== MODULE =====================

    @Test
    void moduleDeclared() {
        List<Decl> d = parse("MODULE(\"smelting\")\nint x;");
        assertThat(d).hasSize(2);
        assertThat(d.get(0)).isInstanceOf(ModuleDecl.class);
        assertThat(((ModuleDecl) d.get(0)).name).isEqualTo("smelting");
    }

    @Test
    void missingModuleNameReportsError() {
        assertThat(errors("MODULE()\nint x;")).isNotEmpty();
    }

    // ===================== import / using / namespace =====================

    @Test
    void importModule() {
        List<Decl> d = parse("MODULE(\"a\")\nimport furnace_lib;");
        assertThat(d.get(1)).isInstanceOf(Ast.ImportDecl.class);
        assertThat(((Ast.ImportDecl) d.get(1)).module).isEqualTo("furnace_lib");
    }

    @Test
    void fromImportFunction() {
        List<Decl> d = parse("MODULE(\"a\")\nfrom furnace_lib import 熔炼;");
        assertThat(d.get(1)).isInstanceOf(Ast.FromImportDecl.class);
        Ast.FromImportDecl fi = (Ast.FromImportDecl) d.get(1);
        assertThat(fi.module).isEqualTo("furnace_lib");
        assertThat(fi.symbol).isEqualTo("熔炼");
    }

    @Test
    void usingNamespaceMember() {
        List<Decl> d = parse("MODULE(\"a\")\nusing 燃料::熔岩;");
        Ast.UsingDecl u = (Ast.UsingDecl) d.get(1);
        assertThat(u.ns).isEqualTo("燃料");
        assertThat(u.member).isEqualTo("熔岩");
    }

    @Test
    void usingTypeAlias() {
        List<Decl> d = parse("MODULE(\"a\")\nusing 燃料表 = map<string, int>;");
        Ast.UsingDecl u = (Ast.UsingDecl) d.get(1);
        assertThat(u.alias).isEqualTo("燃料表");
        assertThat(u.aliasType.toString()).isEqualTo("map<string,int>");
    }

    @Test
    void namespaceBlock() {
        List<Decl> d = parse("MODULE(\"a\")\nnamespace 铁 { int x; }");
        Ast.NamespaceDecl ns = (Ast.NamespaceDecl) d.get(1);
        assertThat(ns.name).isEqualTo("铁");
        assertThat(ns.body).hasSize(1);
    }

    // ===================== 变量声明 =====================

    @Test
    void topLevelVarDecl() {
        List<Decl> d = parse("MODULE(\"a\")\nint 熔炼进度 = 0;");
        Ast.TopLevelVarDecl tv = (Ast.TopLevelVarDecl) d.get(1);
        assertThat(tv.var.name).isEqualTo("熔炼进度");
    }

    @Test
    void vectorGenericType() {
        List<Decl> d = parse("MODULE(\"a\")\nvector<Container> 箱子们;");
        Ast.TopLevelVarDecl tv = (Ast.TopLevelVarDecl) d.get(1);
        assertThat(tv.var.type.toString()).isEqualTo("vector<Container>");
    }

    // ===================== 函数 =====================

    @Test
    void functionDeclared() {
        List<Decl> d = parse("MODULE(\"a\")\nint 加(int a, int b) { return a + b; }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.name).isEqualTo("加");
        assertThat(f.params).hasSize(2);
        assertThat(f.isRunLoop).isFalse();
    }

    @Test
    void runLoopEntry() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid RunLoop(int tick) { return; }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.isRunLoop).isTrue();
        assertThat(f.name).isEqualTo("RunLoop");
    }

    @Test
    void voidReturnType() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid 无返回() { }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.returnType.name).isEqualTo("void");
    }

    // ===================== 控制流 =====================

    @Test
    void ifStatement() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { if (x > 0) { x = 1; } }");
        FuncDecl f = (FuncDecl) d.get(1);
        BlockStmt body = f.body;
        assertThat(body.stmts.get(0)).isInstanceOf(IfStmt.class);
    }

    @Test
    void whileStatement() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { while (x) { x--; } }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.body.stmts.get(0)).isInstanceOf(WhileStmt.class);
    }

    @Test
    void doWhileStatement() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { do { x--; } while (x > 0); }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.body.stmts.get(0)).isInstanceOf(Ast.DoWhileStmt.class);
    }

    @Test
    void classicForStatement() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { for (int i = 0; i < 10; i++) { } }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.body.stmts.get(0)).isInstanceOf(ForStmt.class);
    }

    @Test
    void rangeForStatement() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { for (Item 铁 : 箱子们) { } }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.body.stmts.get(0)).isInstanceOf(RangeForStmt.class);
        RangeForStmt rf = (RangeForStmt) f.body.stmts.get(0);
        assertThat(rf.elemType.name).isEqualTo("Item");
        assertThat(rf.var).isEqualTo("铁");
    }

    @Test
    void switchStatement() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { switch (x) { case 1: y = 1; default: y = 0; } }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.body.stmts.get(0)).isInstanceOf(Ast.SwitchStmt.class);
    }

    @Test
    void returnStatement() {
        List<Decl> d = parse("MODULE(\"a\")\nint f() { return 5; }");
        FuncDecl f = (FuncDecl) d.get(1);
        assertThat(f.body.stmts.get(0)).isInstanceOf(ReturnStmt.class);
    }

    // ===================== 表达式优先级 =====================

    @Test
    void precedenceMulOverAdd() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { x = 1 + 2 * 3; }");
        FuncDecl f = (FuncDecl) d.get(1);
        ExprStmt stmt = (ExprStmt) f.body.stmts.get(0);
        Ast.AssignExpr assign = (Ast.AssignExpr) stmt.expr;
        BinaryExpr add = (BinaryExpr) assign.rhs;
        assertThat(add.op.type()).isEqualTo(TokenType.PLUS);
        // 右操作数是乘法
        assertThat(add.right).isInstanceOf(BinaryExpr.class);
        assertThat(((BinaryExpr) add.right).op.type()).isEqualTo(TokenType.STAR);
    }

    @Test
    void precedenceAndOverOr() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { x = a || b && c; }");
        FuncDecl f = (FuncDecl) d.get(1);
        ExprStmt stmt = (ExprStmt) f.body.stmts.get(0);
        Ast.AssignExpr assign = (Ast.AssignExpr) stmt.expr;
        BinaryExpr or = (BinaryExpr) assign.rhs;
        assertThat(or.op.type()).isEqualTo(TokenType.OR);
        assertThat(or.right).isInstanceOf(BinaryExpr.class);
        assertThat(((BinaryExpr) or.right).op.type()).isEqualTo(TokenType.AND);
    }

    @Test
    void callAndMemberExpression() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { 箱子.canPut(0, 粗铁); }");
        FuncDecl f = (FuncDecl) d.get(1);
        ExprStmt stmt = (ExprStmt) f.body.stmts.get(0);
        Ast.CallExpr call = (Ast.CallExpr) stmt.expr;
        assertThat(call.callee).isInstanceOf(Ast.MemberExpr.class);
        assertThat(call.args).hasSize(2);
    }

    // ===================== 错误码 =====================

    @Test
    void missingSemicolonReportsError() {
        assertThat(errors("MODULE(\"a\")\nint x")).isNotEmpty();
    }

    @Test
    void missingSemicolonReportsS03() {
        // 以前一律报 S02"括号不匹配"，作者会去数括号；缺分号就该说缺分号。
        assertThat(errors("MODULE(\"a\")\nint x"))
                .extracting(CompileError::code)
                .contains(dev.programmablefurnace.compiler.lex.ErrorCode.S03_MISSING_SEMICOLON);
    }

    @Test
    void missingBraceReportsS04() {
        assertThat(errors("MODULE(\"a\")\nvoid f() {"))
                .extracting(CompileError::code)
                .contains(dev.programmablefurnace.compiler.lex.ErrorCode.S04_MISSING_BRACE);
    }

    @Test
    void unmatchedBraceReportsError() {
        assertThat(errors("MODULE(\"a\")\nvoid f() {")).isNotEmpty();
    }

    @Test
    void errorCodeIsSyntaxClass() {
        List<CompileError> errs = errors("MODULE(\"a\")\nvoid f() {");
        assertThat(errs).isNotEmpty();
        assertThat(errs.get(0).code().prefix()).isEqualTo('S');
    }

    @Test
    void literalParsing() {
        List<Decl> d = parse("MODULE(\"a\")\nint x = 42;");
        Ast.TopLevelVarDecl tv = (Ast.TopLevelVarDecl) d.get(1);
        assertThat(tv.var.init).isInstanceOf(LiteralExpr.class);
        LiteralExpr lit = (LiteralExpr) tv.var.init;
        assertThat(lit.value).isEqualTo("42");
    }

    @Test
    void varRefExpression() {
        List<Decl> d = parse("MODULE(\"a\")\nint x = 熔炼;");
        Ast.TopLevelVarDecl tv = (Ast.TopLevelVarDecl) d.get(1);
        assertThat(tv.var.init).isInstanceOf(VarExpr.class);
    }

    // ===================== 花括号初始化列表（F7.1） =====================

    @Test
    void braceListAsCallArg() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { 粗铁流.init({上方箱子}, {熔炉.input}, {tag::粗铁}); }");
        FuncDecl f = (FuncDecl) d.get(1);
        ExprStmt stmt = (ExprStmt) f.body.stmts.get(0);
        Ast.CallExpr call = (Ast.CallExpr) stmt.expr;
        assertThat(call.args).hasSize(3);
        assertThat(call.args.get(0)).isInstanceOf(Ast.BraceListExpr.class);
        assertThat(call.args.get(1)).isInstanceOf(Ast.BraceListExpr.class);
        Ast.BraceListExpr bl = (Ast.BraceListExpr) call.args.get(2);
        assertThat(bl.items).hasSize(1);
        assertThat(bl.items.get(0)).isInstanceOf(Ast.MemberExpr.class); // tag::粗铁
    }

    @Test
    void braceListAsVarInit() {
        List<Decl> d = parse("MODULE(\"a\")\nvector<Item> xs = {铁, 铜};");
        Ast.TopLevelVarDecl tv = (Ast.TopLevelVarDecl) d.get(1);
        assertThat(tv.var.init).isInstanceOf(Ast.BraceListExpr.class);
        Ast.BraceListExpr bl = (Ast.BraceListExpr) tv.var.init;
        assertThat(bl.items).hasSize(2);
    }

    @Test
    void braceListEmpty() {
        List<Decl> d = parse("MODULE(\"a\")\nvoid f() { 粗铁流.init({}); }");
        FuncDecl f = (FuncDecl) d.get(1);
        ExprStmt stmt = (ExprStmt) f.body.stmts.get(0);
        Ast.CallExpr call = (Ast.CallExpr) stmt.expr;
        assertThat(call.args.get(0)).isInstanceOf(Ast.BraceListExpr.class);
        assertThat(((Ast.BraceListExpr) call.args.get(0)).items).isEmpty();
    }
}
