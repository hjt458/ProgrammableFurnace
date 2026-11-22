package dev.programmablefurnace.compiler.sem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.syntax.Ast;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 语义 / 类型检查（草稿 §2.5、T 类错误码）：T01 类型不匹配、T06 void 返回值。 */
class TypeCheckerTest {

    private static List<Ast.Decl> parse(String src) {
        Lexer lx = new Lexer("t.mc", src);
        List<Token> toks = lx.tokenize();
        return new Parser(toks).parseFile();
    }

    private static List<CompileError> check(String src) {
        List<Ast.Decl> d = parse(src);
        return TypeChecker.check(StorageBuilder.build(d), d).errors();
    }

    private static void expectCode(String src, dev.programmablefurnace.compiler.lex.ErrorCode code) {
        List<CompileError> e = check(src);
        assertThat(e).anyMatch(err -> err.code() == code);
    }

    // ===================== 初始化类型不匹配（T01） =====================

    @Test
    void intInit_withStringLiteral_isError() {
        expectCode("MODULE(\"a\")\nint x = \"abc\";", dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    @Test
    void boolInit_withIntLiteral_isError() {
        expectCode("MODULE(\"a\")\nbool b = 5;", dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    @Test
    void numericInit_acrossNumericTypes_ok() {
        assertThat(check("MODULE(\"a\")\nint a = 1;\ndouble d = a;\nfloat f = 2.0;")).isEmpty();
    }

    @Test
    void stringInit_ok() {
        assertThat(check("MODULE(\"a\")\nstring s = \"hi\";")).isEmpty();
    }

    @Test
    void autoAndContainerInit_lenient() {
        // auto / 容器类型（不透明）→ 不误报
        assertThat(check("MODULE(\"a\")\nauto a = 5;\nvector<Item> xs = searchByTag(self);")).isEmpty();
    }

    // ===================== 函数返回值 =====================

    @Test
    void functionReturnType_matched_ok() {
        assertThat(check("MODULE(\"a\")\nint 熔炼() { return 1; }\nint x = 熔炼();")).isEmpty();
    }

    @Test
    void functionReturnType_mismatch_isError() {
        expectCode("MODULE(\"a\")\nint 熔炼() { return \"x\"; }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    @Test
    void voidFunctionReturningValue_isError() {
        expectCode("MODULE(\"a\")\nvoid f() { return 5; }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T06_BAD_RETURN_TYPE);
    }

    @Test
    void voidFunctionPlainReturn_ok() {
        assertThat(check("MODULE(\"a\")\nvoid f() { return; }")).isEmpty();
    }

    // ===================== 运算符操作数类型 =====================

    @Test
    void logicalOp_needsBool() {
        expectCode("MODULE(\"a\")\nbool b = true && 5;",
                dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    @Test
    void logicalOp_ok() {
        assertThat(check("MODULE(\"a\")\nbool b = true && false;")).isEmpty();
    }

    @Test
    void compareOp_needsNumeric() {
        expectCode("MODULE(\"a\")\nbool b = \"a\" < 1;",
                dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    @Test
    void compareOp_ok() {
        assertThat(check("MODULE(\"a\")\nbool b = 1 < 2;")).isEmpty();
    }

    @Test
    void notOp_needsBool() {
        expectCode("MODULE(\"a\")\nbool b = !5;",
                dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    // ===================== 赋值语句 / 局部作用域 =====================

    @Test
    void assignmentStatement_mismatch_isError() {
        expectCode("MODULE(\"a\")\nvoid f() { int x; x = \"abc\"; }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    @Test
    void localShadow_usesInnerScope() {
        // 内层块声明 string x 遮蔽外层 int x；s 赋 string → 无错
        assertThat(check("MODULE(\"a\")\nvoid f() { int x = 1; { string x = \"a\"; string s = x; } }")).isEmpty();
    }

    // ===================== break / continue 作用域（T07） =====================

    @Test
    void breakOutsideLoop_isError() {
        expectCode("MODULE(\"a\")\nvoid f() { break; }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T07_SCOPE_VIOLATION);
    }

    @Test
    void continueOutsideLoop_isError() {
        expectCode("MODULE(\"a\")\nvoid f() { continue; }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T07_SCOPE_VIOLATION);
    }

    @Test
    void breakInsideSwitch_isAllowed() {
        assertThat(check("MODULE(\"a\")\nvoid f() { int x = 1; switch (x) { case 1: break; default: break; } }"))
                .isEmpty();
    }

    @Test
    void continueInsideSwitch_isError() {
        // continue 只认循环：switch 里写 continue 是常见笔误
        expectCode("MODULE(\"a\")\nvoid f() { int x = 1; switch (x) { case 1: continue; } }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T07_SCOPE_VIOLATION);
    }

    @Test
    void breakInsideLoop_isAllowed() {
        assertThat(check("MODULE(\"a\")\nvoid f() { for (int i = 0; i < 3; i = i + 1) { break; } }")).isEmpty();
    }

    @Test
    void loopOutsideFunction_doesNotLeakToInnerFunction() {
        // 函数体内的 break 不能借用"外层"循环（函数是独立边界）
        expectCode("MODULE(\"a\")\nvoid f() { for (int i = 0; i < 3; i = i + 1) { } }\nvoid g() { break; }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T07_SCOPE_VIOLATION);
    }

    // ===================== T02 未声明变量（2026-11-17 启用） =====================

    @Test
    void readOfUndeclaredName_isError() {
        expectCode("MODULE(\"a\")\nvoid f() { int x = 没声明过的东西; }",
                dev.programmablefurnace.compiler.lex.ErrorCode.T02_UNDECLARED_VARIABLE);
    }

    @Test
    void undeclaredName_isReportedOncePerName() {
        // 同一个拼错的名字用两遍，只报一次（否则一处笔误会刷出一屏同样的错）
        List<CompileError> e = check("MODULE(\"a\")\nvoid f() { int x = 缺; int y = 缺; }");
        assertThat(e).filteredOn(err -> err.code() == dev.programmablefurnace.compiler.lex.ErrorCode.T02_UNDECLARED_VARIABLE)
                .hasSize(1);
    }

    @Test
    void globalDeclaredAfterUse_isNotT02() {
        // 脚本允许先写函数、后写全局变量：判据是"全表里有没有这个名字"，不是"此前有没有声明"
        assertThat(check("MODULE(\"a\")\nvoid f() { g = 1; }\nint g = 0;")).isEmpty();
    }

    @Test
    void callOfDeclaredFunction_isNotT02() {
        assertThat(check("MODULE(\"a\")\nvoid helper() { }\nvoid f() { helper(); }")).isEmpty();
    }

    // ===================== 类型别名（using 新名 = 类型） =====================

    @Test
    void alias_isUsableAsDeclaredType() {
        assertThat(check("MODULE(\"a\")\nusing 整数 = int;\n整数 x = 1 + 2;")).isEmpty();
    }

    @Test
    void alias_stillChecksUnderlyingType() {
        // 别名不是"免检牌"：展开成 int 之后，字符串初始化照样报 T01
        expectCode("MODULE(\"a\")\nusing 整数 = int;\n整数 x = \"abc\";",
                dev.programmablefurnace.compiler.lex.ErrorCode.T01_TYPE_MISMATCH);
    }

    @Test
    void alias_worksInFunctionSignature() {
        // 形参与返回类型也可以用别名
        assertThat(check("MODULE(\"a\")\nusing 整数 = int;\n整数 twice(整数 n) { return n + n; }")).isEmpty();
    }

    // ===================== 非关键字类型名（Slot / 游戏类型） =====================

    @Test
    void slot_isUsableAsDeclaredType() {
        // `Slot` 不是关键字，靠"类型名 + 变量名"两个标识符连着出现来识别
        assertThat(check("MODULE(\"a\")\nvoid f() { Slot 槽 = furnace.input[0]; 槽.setItem(Item(\"minecraft:coal\")); }")).isEmpty();
    }

    @Test
    void slotDeclaration_doesNotBreakExpressionStatements() {
        assertThat(check("MODULE(\"a\")\nvoid f() { furnace.ignite(); }")).isEmpty();
    }
}
