package dev.programmablefurnace.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * {@link CompilerAnalyzer} 单元测试：用真实词法 + 语法分析判定内容状态。
 * 覆盖：空白、编译失败（词法/语法）、可执行（含 RunLoop）、依赖（无 RunLoop）、嵌套 namespace。
 */
class CompilerAnalyzerTest {

    private final CompilerAnalyzer analyzer = new CompilerAnalyzer();

    // ===================== 空白 =====================

    @Test
    void blank_forNull() {
        assertThat(analyzer.analyze(null)).isEqualTo(ContentState.BLANK);
    }

    @Test
    void blank_forEmpty() {
        assertThat(analyzer.analyze("")).isEqualTo(ContentState.BLANK);
    }

    @Test
    void blank_forWhitespaceOnly() {
        assertThat(analyzer.analyze("   \n  \n")).isEqualTo(ContentState.BLANK);
    }

    // ===================== 编译失败 =====================

    @Test
    void failed_forLexError() {
        // 非法字符 → 词法错误 L01 → 编译期出错即失败
        assertThat(analyzer.analyze("MODULE(\"a\")\nint x; $")).isEqualTo(ContentState.FAILED);
    }

    @Test
    void failed_forSyntaxError() {
        // 缺少分号 → 语法错误 S → 失败
        assertThat(analyzer.analyze("MODULE(\"a\")\nint x")).isEqualTo(ContentState.FAILED);
    }

    @Test
    void failed_forUnclosedFunction() {
        assertThat(analyzer.analyze("MODULE(\"a\")\nvoid RunLoop(int tick) {")).isEqualTo(ContentState.FAILED);
    }

    // ===================== 可执行（含 RunLoop） =====================

    @Test
    void executable_whenRunLoopPresent() {
        assertThat(analyzer.analyze("MODULE(\"smelting\")\nvoid RunLoop(int tick) { return; }"))
                .isEqualTo(ContentState.EXECUTABLE);
    }

    @Test
    void executable_whenRunLoopInNamespace() {
        // RunLoop 嵌套在 namespace 内也算可执行文件
        assertThat(analyzer.analyze("MODULE(\"a\")\nnamespace 炉 { void RunLoop(int tick) { } }"))
                .isEqualTo(ContentState.EXECUTABLE);
    }

    // ===================== 依赖（无 RunLoop 但合法） =====================

    @Test
    void dependency_whenNoRunLoop() {
        assertThat(analyzer.analyze("MODULE(\"lib\")\nint 熔炼(int n) { return n + 1; }"))
                .isEqualTo(ContentState.DEPENDENCY);
    }

    @Test
    void dependency_whenImportsAndVarsOnly() {
        assertThat(analyzer.analyze("MODULE(\"lib\")\nimport furnace_lib;\nint 进度 = 0;"))
                .isEqualTo(ContentState.DEPENDENCY);
    }

    @Test
    void dependency_whenDraftF7_1ItemStreamExample() {
        // 草稿 F7.1 ItemStream 示例：含裸花括号实参列表，无 RunLoop → 依赖库
        String draft = "MODULE(\"smelting\")\n"
                + "ItemStream 粗铁流;\n"
                + "void 设定() {\n"
                + "  粗铁流.init({上方箱子}, {熔炉.输入槽}, {标签::粗铁});\n"
                + "  粗铁流.advance();\n"
                + "}\n";
        assertThat(analyzer.analyze(draft)).isEqualTo(ContentState.DEPENDENCY);
    }

    @Test
    void executable_whenRunLoopWithBraceList() {
        // RunLoop 内用花括号列表初始化 vector → 可执行
        assertThat(analyzer.analyze("MODULE(\"a\")\nvoid RunLoop(int tick) { vector<Item> xs = {铁, 铜}; }"))
                .isEqualTo(ContentState.EXECUTABLE);
    }

    // ===================== 存储表（§2.4）：防同名 / namespace 分区 =====================

    @Test
    void failed_whenDuplicateVariable() {
        // 同作用域重名变量 → 存储表错误（T05）→ 编译失败
        assertThat(analyzer.analyze("MODULE(\"a\")\nint 进度;\nint 进度;"))
                .isEqualTo(ContentState.FAILED);
    }

    @Test
    void executable_whenSameNameInDifferentNamespaces() {
        // namespace 分区隔离同名符号 → 无错误；两处均有 RunLoop → 可执行
        assertThat(analyzer.analyze("MODULE(\"a\")\n"
                + "namespace 高炉 { void RunLoop(int tick) {} }\n"
                + "namespace 平炉 { void RunLoop(int tick) {} }"))
                .isEqualTo(ContentState.EXECUTABLE);
    }

    // ===================== 链接期绑定（§2.2③）：把变量当函数调用 → 编译失败 =====================

    @Test
    void failed_whenCallVariableAsFunction() {
        assertThat(analyzer.analyze("MODULE(\"a\")\nint 进度;\nvoid f() { 进度(1); }"))
                .isEqualTo(ContentState.FAILED);
    }

    // ===================== 语义/类型检查（§2.5、T 类）：T01 / T06 → 编译失败 =====================

    @Test
    void failed_whenTypeMismatch() {
        assertThat(analyzer.analyze("MODULE(\"a\")\nint x = \"abc\";"))
                .isEqualTo(ContentState.FAILED);
    }

    @Test
    void failed_whenVoidReturnsValue() {
        assertThat(analyzer.analyze("MODULE(\"a\")\nvoid f() { return 5; }"))
                .isEqualTo(ContentState.FAILED);
    }

    // ===================== M 类单文件校验（§1.1 / §4.7）：M01 缺模块声明 =====================

    @Test
    void failed_whenMissingModuleDeclaration() {
        assertThat(analyzer.analyze("int x = 1;"))
                .isEqualTo(ContentState.FAILED);
    }

    // ===================== 畸形源码止血（2026-11-16 批次 0）：不抛异常，只判失败 =====================

    @Test
    void failed_forMalformedBinaryExpression_withoutThrowing() {
        // 旧实现：parsePrimary 返回 null 后仍取 expr.pos → NPE 一路冒到服务端（远程崩服）
        assertThat(analyzer.analyze("MODULE(\"a\")\nvoid RunLoop(int t) { & (1); }"))
                .isEqualTo(ContentState.FAILED);
    }

    @Test
    void failed_forDeeplyNestedExpression_withoutStackOverflow() {
        String deep = "(".repeat(400) + "1" + ")".repeat(400);
        assertThat(analyzer.analyze("MODULE(\"a\")\nvoid RunLoop(int t) { int x = " + deep + "; }"))
                .isEqualTo(ContentState.FAILED);
    }

    @Test
    void failed_forDeeplyNestedBlocks_withoutStackOverflow() {
        // 600 层块不一定会压穿栈（取决于 JVM 栈大小），所以只断言"异常不外泄"
        String deep = "{".repeat(600) + "}".repeat(600);
        assertThatCode(() -> analyzer.analyze("MODULE(\"a\")\nvoid RunLoop(int t) " + deep))
                .doesNotThrowAnyException();
    }

    // ===================== 错误可见性（2026-11-16 批次 1）：失败必须带回错误 =====================

    @Test
    void failedResult_carriesErrors() {
        // 批次 1 之前 CompileResult 只有三态，错误码与位置全部丢弃，界面上只剩"失败"两个字
        CompilerAnalyzer.CompileResult result = CompilerAnalyzer.compile("MODULE(\"a\")\nint x");

        assertThat(result.state()).isEqualTo(ContentState.FAILED);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void failedResult_errorTextHasCodeAndPosition() {
        CompilerAnalyzer.CompileResult result = CompilerAnalyzer.compile("MODULE(\"a\")\nint x");

        String first = result.errors().get(0).toString();
        assertThat(first).contains("S");            // 词法/语法阶段都以错误码开头（L/S/T/M）
        assertThat(first).contains("<classify>");   // classify 阶段无真文件名，用占位名定位
    }

    @Test
    void failedResult_fromSemanticStage_carriesErrors() {
        // 类型检查阶段（T 类）同样要带回错误，而不是只标 FAILED
        CompilerAnalyzer.CompileResult result = CompilerAnalyzer.compile("MODULE(\"a\")\nint x = \"abc\";");

        assertThat(result.state()).isEqualTo(ContentState.FAILED);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0).toString()).contains("T01");
    }

    @Test
    void successfulResult_hasNoErrors() {
        CompilerAnalyzer.CompileResult result =
                CompilerAnalyzer.compile("MODULE(\"a\")\nvoid RunLoop(int t) { }");

        assertThat(result.state()).isEqualTo(ContentState.EXECUTABLE);
        assertThat(result.errors()).isEmpty();
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void blankResult_hasNoErrors() {
        CompilerAnalyzer.CompileResult result = CompilerAnalyzer.compile("  \n ");

        assertThat(result.state()).isEqualTo(ContentState.BLANK);
        assertThat(result.errors()).isEmpty();
    }

    // ===================== 入口契约（M06） =====================

    @Test
    void failed_whenRunLoopReturnsValue() {
        // 入口返回值会被 Executor 直接丢弃：声明成 int 属于"名义上有入口、实际不可用"。
        CompilerAnalyzer.CompileResult result =
                CompilerAnalyzer.compile("MODULE(\"a\")\nint RunLoop() { return 1; }");

        assertThat(result.state()).isEqualTo(ContentState.FAILED);
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().get(0).code())
                .isEqualTo(dev.programmablefurnace.compiler.lex.ErrorCode.M06_MISSING_EXEC_ENTRY);
    }

    @Test
    void executable_whenRunLoopTakesTickParam() {
        // 语言约定的写法是 RunLoop(int tick)，形参不影响可执行判定（运行时补默认值）。
        CompilerAnalyzer.CompileResult result =
                CompilerAnalyzer.compile("MODULE(\"a\")\nvoid RunLoop(int tick) { int x = tick; }");

        assertThat(result.state()).isEqualTo(ContentState.EXECUTABLE);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void bundledExampleScript_compilesToExecutable() throws Exception {
        // 随包示例是玩家第一次见到这门语言时的唯一范本：它编译不过，等于整个 mod 的示例坏了。
        String src;
        try (var in = getClass().getResourceAsStream(
                "/programmablefurnace/scripts/furnace_smelting.mc")) {
            assertThat(in).as("示例脚本必须随 jar 打包").isNotNull();
            src = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        CompilerAnalyzer.CompileResult result = CompilerAnalyzer.compile(src);

        assertThat(result.errors()).as("示例脚本不能有编译错误").isEmpty();
        assertThat(result.state()).isEqualTo(ContentState.EXECUTABLE);
    }

    // ===================== 链接期：多模块 import（批次 E） =====================

    @Test
    void compileModules_linksImportedFunction() {
        // 依赖库里的模块按 MODULE 名互相可见：from 导入后按裸名调用，执行器的声明表里有它
        CompilerAnalyzer.ModulesResult r = CompilerAnalyzer.compileModules(java.util.List.of(
                new CompilerAnalyzer.ModuleSource("工具.mc",
                        "MODULE(\"工具\")\nint 加(int a, int b) { return a + b; }"),
                new CompilerAnalyzer.ModuleSource("主.mc",
                        "MODULE(\"主\")\nfrom 工具 import 加;\nint 结果 = 0;\n"
                                + "void RunLoop(int tick) { 结果 = 加(1, 2); }")));

        assertThat(r.errors()).as("跨模块调用不应报 T03").isEmpty();
        assertThat(r.state()).isEqualTo(ContentState.EXECUTABLE);
        assertThat(r.entryModule()).isEqualTo("主");
        assertThat(r.decls()).anyMatch(d -> d instanceof dev.programmablefurnace.compiler.syntax.Ast.FuncDecl f
                && f.name.equals("加"));
    }

    @Test
    void compileModules_reportsMissingModule() {
        CompilerAnalyzer.ModulesResult r = CompilerAnalyzer.compileModules(java.util.List.of(
                new CompilerAnalyzer.ModuleSource("主.mc",
                        "MODULE(\"主\")\nfrom 不存在 import 某函数;\n"
                                + "void RunLoop(int tick) { 某函数(1); }")));

        assertThat(r.state()).isEqualTo(ContentState.FAILED);
        assertThat(r.errors()).extracting(CompileError::code)
                .contains(ErrorCode.M03_MODULE_NOT_FOUND);
    }

    @Test
    void compileModules_reportsMissingSymbol() {
        CompilerAnalyzer.ModulesResult r = CompilerAnalyzer.compileModules(java.util.List.of(
                new CompilerAnalyzer.ModuleSource("工具.mc", "MODULE(\"工具\")\nint 甲 = 1;"),
                new CompilerAnalyzer.ModuleSource("主.mc",
                        "MODULE(\"主\")\nfrom 工具 import 没有这个;\n"
                                + "void RunLoop(int tick) { 没有这个(1); }")));

        assertThat(r.state()).isEqualTo(ContentState.FAILED);
        assertThat(r.errors()).extracting(CompileError::code)
                .contains(ErrorCode.M04_IMPORT_SYMBOL_NOT_FOUND);
    }

    @Test
    void compileModules_withoutEntryModule_isDependency() {
        CompilerAnalyzer.ModulesResult r = CompilerAnalyzer.compileModules(java.util.List.of(
                new CompilerAnalyzer.ModuleSource("甲.mc", "MODULE(\"甲\")\nint a = 1;"),
                new CompilerAnalyzer.ModuleSource("乙.mc", "MODULE(\"乙\")\nint b = 2;")));

        assertThat(r.state()).isEqualTo(ContentState.DEPENDENCY);
        assertThat(r.entryModule()).isNull();
        assertThat(r.modules()).hasSize(2);
    }
}
