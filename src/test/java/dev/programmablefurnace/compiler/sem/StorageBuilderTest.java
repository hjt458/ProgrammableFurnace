package dev.programmablefurnace.compiler.sem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.syntax.Ast;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 存储表（符号表，草稿 §2.4）构建测试：模块边界、作用域（模块级 + namespace 分区 + 函数级/块级）、
 * 符号条目附加属性、防同名、import / using 登记、名称解析。
 */
class StorageBuilderTest {

    /** 词法 + 语法解析出顶层声明。 */
    private static List<Ast.Decl> parse(String src) {
        Lexer lx = new Lexer("t.mc", src);
        List<Token> toks = lx.tokenize();
        Parser ps = new Parser(toks);
        return ps.parseFile();
    }

    private static StorageTable build(String src) {
        return StorageBuilder.build(parse(src));
    }

    // ===================== 模块边界 =====================

    @Test
    void moduleName_isBoundary() {
        StorageTable t = build("MODULE(\"smelting\")\nint 进度 = 0;\nvoid f() {}");
        assertThat(t.moduleName()).isEqualTo("smelting");
        assertThat(t.errors()).isEmpty();
    }

    @Test
    void duplicateModule_isErrorM02() {
        StorageTable t = build("MODULE(\"a\")\nMODULE(\"b\")");
        assertThat(t.errors()).hasSize(1);
        assertThat(t.errors().get(0).code()).isEqualTo(
                dev.programmablefurnace.compiler.lex.ErrorCode.M02_DUPLICATE_MODULE);
    }

    @Test
    void missingModule_isErrorM01() {
        StorageTable t = build("int x = 1;");
        assertThat(t.errors()).hasSize(1);
        assertThat(t.errors().get(0).code()).isEqualTo(
                dev.programmablefurnace.compiler.lex.ErrorCode.M01_MISSING_MODULE);
    }

    // ===================== 顶层符号：变量 / 函数 / 附加属性 =====================

    @Test
    void topLevelVar_recordsTypeAndInit() {
        StorageTable t = build("MODULE(\"a\")\nint 进度 = 5;");
        Symbol s = t.resolve("进度");
        assertThat(s).isNotNull();
        assertThat(s.kind).isEqualTo(Symbol.Kind.VAR);
        assertThat(s.type).isEqualTo("int");
        assertThat(s.init).isEqualTo("5");
        assertThat(s.scope).isEqualTo("<module>");
    }

    @Test
    void func_recordsReturnTypeParamsAndRunLoop() {
        StorageTable t = build("MODULE(\"a\")\nvoid RunLoop(int tick) {}");
        Symbol s = t.resolve("RunLoop");
        assertThat(s.kind).isEqualTo(Symbol.Kind.FUNC);
        assertThat(s.type).isEqualTo("void");
        assertThat(s.paramTypes).containsExactly("int");
        assertThat(s.isRunLoop).isTrue();
    }

    @Test
    void nonRunLoopFunc_marksIsRunLoopFalse() {
        StorageTable t = build("MODULE(\"a\")\nint 熔炼(int n) { return n; }");
        assertThat(t.resolve("熔炼").isRunLoop).isFalse();
    }

    // ===================== 作用域 / namespace 分区（防同名） =====================

    @Test
    void sameNameInDifferentNamespaces_noError() {
        StorageTable t = build("MODULE(\"a\")\nnamespace 高炉 { int 进度; }\nnamespace 平炉 { int 进度; }");
        assertThat(t.errors()).isEmpty();
        assertThat(t.resolveQualified("高炉::进度")).isNotNull();
        assertThat(t.resolveQualified("平炉::进度")).isNotNull();
    }

    @Test
    void duplicateSameScope_isErrorT05() {
        StorageTable t = build("MODULE(\"a\")\nint 进度;\nint 进度;");
        assertThat(t.errors()).hasSize(1);
        assertThat(t.errors().get(0).code()).isEqualTo(
                dev.programmablefurnace.compiler.lex.ErrorCode.T05_VARIABLE_REDEFINITION);
    }

    @Test
    void duplicateFunction_isErrorT04() {
        // 同名同参数才算冲突（不同参数为合法重载）
        StorageTable t = build("MODULE(\"a\")\nvoid f(int x) {}\nint f(int x) {}");
        assertThat(t.errors()).hasSize(1);
        assertThat(t.errors().get(0).code()).isEqualTo(
                dev.programmablefurnace.compiler.lex.ErrorCode.T04_OVERLOAD_CONFLICT);
    }

    // ===================== 函数级 / 块级作用域 =====================

    @Test
    void localVar_shadowsOuter_withoutError() {
        // 块内局部变量与顶层变量同名 → 遮蔽，不算重复定义
        StorageTable t = build("MODULE(\"a\")\nint 进度;\nvoid f() { int 进度 = 1; }");
        assertThat(t.errors()).isEmpty();
        assertThat(t.allSymbols()).filteredOn(s -> s.name.equals("进度")).hasSize(2);
    }

    @Test
    void forLoop_scopesItsVar() {
        StorageTable t = build("MODULE(\"a\")\nvoid f() { for (int i = 0; i < 3; i++) {} }");
        assertThat(t.errors()).isEmpty();
        assertThat(t.allSymbols()).filteredOn(s -> s.name.equals("i")).hasSize(1);
    }

    @Test
    void rangeFor_registersLoopVar() {
        StorageTable t = build("MODULE(\"a\")\nvoid f() { for (Item 铁 : 箱子们) {} }");
        assertThat(t.errors()).isEmpty();
        assertThat(t.allSymbols()).filteredOn(s -> s.name.equals("铁")).hasSize(1);
    }

    // ===================== import / using 登记 =====================

    @Test
    void imports_recorded_wholeAndSingle() {
        StorageTable t = build("MODULE(\"a\")\nimport furnace_lib;\nfrom furnace_lib import 熔炼;");
        assertThat(t.imports()).hasSize(2);
        assertThat(t.imports().get(0).module()).isEqualTo("furnace_lib");
        assertThat(t.imports().get(0).wholeModule()).isTrue();
        assertThat(t.imports().get(1).symbol()).isEqualTo("熔炼");
        assertThat(t.imports().get(1).wholeModule()).isFalse();
    }

    @Test
    void usingTypeAlias_registersTypeSymbol() {
        StorageTable t = build("MODULE(\"a\")\nusing 铁块 = Item;");
        Symbol s = t.resolve("铁块");
        assertThat(s.kind).isEqualTo(Symbol.Kind.TYPE);
        assertThat(s.type).isEqualTo("Item");
    }

    @Test
    void usingNsMember_recordsUsingRef() {
        StorageTable t = build("MODULE(\"a\")\nusing 依赖::熔炼;");
        assertThat(t.usingRefs()).hasSize(1);
        assertThat(t.usingRefs().get(0).ns()).isEqualTo("依赖");
        assertThat(t.usingRefs().get(0).member()).isEqualTo("熔炼");
    }
}
