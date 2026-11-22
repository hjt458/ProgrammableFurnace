package dev.programmablefurnace.compiler.sem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.syntax.Ast;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 符号绑定（链接期，草稿 §2.2 ③）测试：声明绑定到修饰名、引用解析、未解析按外部、把变量当函数调用报错。
 */
class SymbolBinderTest {

    private static List<Ast.Decl> parse(String src) {
        Lexer lx = new Lexer("t.mc", src);
        List<Token> toks = lx.tokenize();
        return new Parser(toks).parseFile();
    }

    private static SymbolBinder bind(String src) {
        List<Ast.Decl> decls = parse(src);
        return SymbolBinder.bind(StorageBuilder.build(decls), decls);
    }

    // ===================== 声明绑定 =====================

    @Test
    void funcAndVar_boundToMangledNames() {
        SymbolBinder b = bind("MODULE(\"smelting\")\nint 进度 = 0;\nvoid RunLoop(int tick) {}");
        assertThat(b.errors()).isEmpty();
        // 函数（含 RunLoop）与变量均被绑定
        assertThat(b.funcManglings()).hasSize(1);
        assertThat(b.varManglings()).hasSize(2); // 进度 + 形参 tick
        String rl = b.funcManglings().values().iterator().next();
        assertThat(rl).isEqualTo("PF/smelting/RunLoop(int)");
    }

    @Test
    void functionInNamespace_mangledWithScope() {
        SymbolBinder b = bind("MODULE(\"a\")\nnamespace 炉 { void 设定() {} }");
        assertThat(b.funcManglings()).hasSize(1);
        assertThat(b.funcManglings().values().iterator().next()).isEqualTo("PF/a/炉/设定()");
    }

    @Test
    void overloadedFunctions_distinctMangled() {
        SymbolBinder b = bind("MODULE(\"a\")\nint 熔炼(int n) { return n; }\nint 熔炼(int a, int b) { return a + b; }");
        assertThat(b.funcManglings()).hasSize(2);
        assertThat(b.funcManglings().values())
                .containsExactlyInAnyOrder("PF/a/熔炼(int)", "PF/a/熔炼(int,int)");
    }

    // ===================== 引用解析 =====================

    @Test
    void callLocalFunction_tracksReferenced() {
        SymbolBinder b = bind("MODULE(\"a\")\nint 熔炼(int n) { return n; }\nvoid f() { int x = 熔炼(1); }");
        assertThat(b.errors()).isEmpty();
        assertThat(b.referencedSymbols()).contains("熔炼");
    }

    @Test
    void unknownBareName_treatedAsExternal_noError() {
        // 内置库/导入模块（F6 未定稿）→ 外部引用，不误报
        SymbolBinder b = bind("MODULE(\"a\")\nvoid f() { searchByTag(self, \"燃料\"); }");
        assertThat(b.errors()).isEmpty();
        assertThat(b.referencedExternals()).contains("searchByTag", "self");
    }

    @Test
    void objectMethodCall_noBindError() {
        // 对象方法 obj.method（.）→ 运行期派发；obj 本地变量被引用
        SymbolBinder b = bind("MODULE(\"a\")\nContainer 箱子 = getContainer(坐标);\n"
                + "void f() { 箱子.get(0); }");
        assertThat(b.errors()).isEmpty();
        assertThat(b.referencedSymbols()).contains("箱子");
    }

    @Test
    void draftF7_1_externalsAndNoError() {
        SymbolBinder b = bind("MODULE(\"smelting\")\nItemStream 粗铁流;\n"
                + "void 设定() { 粗铁流.init({上方箱子}, {熔炉.input}, {tag::粗铁}); }");
        assertThat(b.errors()).isEmpty();
        assertThat(b.referencedSymbols()).contains("粗铁流");
    }

    // ===================== 链接期错误 =====================

    @Test
    void callVariableAsFunction_isLinkError() {
        SymbolBinder b = bind("MODULE(\"a\")\nint 进度;\nvoid f() { 进度(1); }");
        assertThat(b.errors()).hasSize(1);
        assertThat(b.errors().get(0).code()).isEqualTo(
                dev.programmablefurnace.compiler.lex.ErrorCode.T03_UNDECLARED_FUNCTION);
    }
}
