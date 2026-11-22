package dev.programmablefurnace.compiler.sem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.syntax.Ast;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 跨模块 import 解析（草稿 §4 M 类）：M03 找不到模块 / M04 导入符号不存在 / M05 循环导入。 */
class ImportResolverTest {

    private static List<Ast.Decl> parse(String src) {
        Lexer lx = new Lexer("t.mc", src);
        List<Token> toks = lx.tokenize();
        return new Parser(toks).parseFile();
    }

    private static StorageTable storage(String src) {
        return StorageBuilder.build(parse(src));
    }

    private static List<CompileError> resolve(String currentSrc, ModuleRegistry reg) {
        StorageTable t = storage(currentSrc);
        ImportOptimizer opt = ImportOptimizer.optimize(t, SymbolBinder.bind(t, parse(currentSrc)));
        return ImportResolver.resolve(t, opt, reg).errors();
    }

    private static ModuleRegistry with(String name, String src) {
        ModuleRegistry r = new ModuleRegistry();
        r.register(name, storage(src));
        return r;
    }

    // ===================== M03 找不到模块 =====================

    @Test
    void wholeImport_missingModule_isM03() {
        List<CompileError> e = resolve(
                "MODULE(\"a\")\nimport furnace_lib;\nvoid f() { furnace_lib.熔炼(); }",
                new ModuleRegistry());
        assertThat(e).hasSize(1);
        assertThat(e.get(0).code()).isEqualTo(ErrorCode.M03_MODULE_NOT_FOUND);
    }

    @Test
    void fromImport_missingModule_isM03() {
        List<CompileError> e = resolve(
                "MODULE(\"a\")\nfrom furnace_lib import 熔炼;\nvoid f() { 熔炼(); }",
                new ModuleRegistry());
        assertThat(e).hasSize(1);
        assertThat(e.get(0).code()).isEqualTo(ErrorCode.M03_MODULE_NOT_FOUND);
    }

    // ===================== M04 导入符号不存在 =====================

    @Test
    void wholeImport_missingMember_isM04() {
        // furnace_lib 有 熔炼 但没有 精炼
        ModuleRegistry reg = with("furnace_lib", "MODULE(\"furnace_lib\")\nint 熔炼() { return 1; }");
        List<CompileError> e = resolve(
                "MODULE(\"a\")\nimport furnace_lib;\nvoid f() { furnace_lib.精炼(); }", reg);
        assertThat(e).hasSize(1);
        assertThat(e.get(0).code()).isEqualTo(ErrorCode.M04_IMPORT_SYMBOL_NOT_FOUND);
    }

    @Test
    void fromImport_missingSymbol_isM04() {
        ModuleRegistry reg = with("furnace_lib", "MODULE(\"furnace_lib\")\nint 粗铁() { return 1; }");
        List<CompileError> e = resolve(
                "MODULE(\"a\")\nfrom furnace_lib import 熔炼;\nvoid f() { 熔炼(); }", reg);
        assertThat(e).hasSize(1);
        assertThat(e.get(0).code()).isEqualTo(ErrorCode.M04_IMPORT_SYMBOL_NOT_FOUND);
    }

    @Test
    void fromImport_namespaceMemberMissing_isM04() {
        ModuleRegistry reg = with("lib", "MODULE(\"lib\")\nnamespace 炉 { int 粗铁; }");
        List<CompileError> e = resolve(
                "MODULE(\"a\")\nfrom lib import 炉;\nvoid f() { 炉.精炼(); }", reg);
        assertThat(e).hasSize(1);
        assertThat(e.get(0).code()).isEqualTo(ErrorCode.M04_IMPORT_SYMBOL_NOT_FOUND);
    }

    // ===================== 有效解析 / 未引用 import =====================

    @Test
    void validImports_noError() {
        ModuleRegistry reg = with("furnace_lib", "MODULE(\"furnace_lib\")\nint 熔炼() { return 1; }");
        List<CompileError> e = resolve(
                "MODULE(\"a\")\nfrom furnace_lib import 熔炼;\nvoid f() { int x = 熔炼(); }", reg);
        assertThat(e).isEmpty();
    }

    @Test
    void unusedImportOfMissingModule_noError() {
        // 未引用的 import 被优化器砍掉 → 目标缺失也不报错
        List<CompileError> e = resolve(
                "MODULE(\"a\")\nimport dead_lib;\nvoid f() {}", new ModuleRegistry());
        assertThat(e).isEmpty();
    }

    // ===================== M05 循环导入 =====================

    @Test
    void cyclicImport_isM05() {
        ModuleRegistry reg = new ModuleRegistry();
        reg.register("a", storage("MODULE(\"a\")\nimport b;"));
        reg.register("b", storage("MODULE(\"b\")\nimport a;"));
        List<CompileError> e = resolve("MODULE(\"a\")\nimport b;", reg);
        assertThat(e).anyMatch(err -> err.code() == ErrorCode.M05_CYCLIC_IMPORT);
    }
}
