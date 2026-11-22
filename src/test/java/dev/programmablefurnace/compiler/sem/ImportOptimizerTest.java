package dev.programmablefurnace.compiler.sem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.syntax.Ast;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** import 最小单位优化（草稿 §2.2 ④ / §2.4）：只保留实际被引用的导入符号，砍掉未引用的。 */
class ImportOptimizerTest {

    private static List<Ast.Decl> parse(String src) {
        Lexer lx = new Lexer("t.mc", src);
        List<Token> toks = lx.tokenize();
        return new Parser(toks).parseFile();
    }

    private static ImportOptimizer optimize(String src) {
        List<Ast.Decl> d = parse(src);
        StorageTable t = StorageBuilder.build(d);
        return ImportOptimizer.optimize(t, SymbolBinder.bind(t, d));
    }

    // ===================== 全量 import =====================

    @Test
    void wholeImport_unused_pruned() {
        ImportOptimizer o = optimize("MODULE(\"a\")\nimport unused_lib;\nvoid f() {}");
        assertThat(o.unusedImports()).hasSize(1);
        assertThat(o.unusedImports().get(0).module()).isEqualTo("unused_lib");
        assertThat(o.usedImports()).isEmpty();
        assertThat(o.minimalImportSet()).isEmpty();
    }

    @Test
    void wholeImport_used_keepsReferencedMembers() {
        ImportOptimizer o = optimize("MODULE(\"a\")\nimport furnace_lib;\nvoid f() { furnace_lib.熔炼(); }");
        assertThat(o.unusedImports()).isEmpty();
        assertThat(o.usedImports()).hasSize(1);
        ImportOptimizer.ImportUse u = o.usedImports().get(0);
        assertThat(u.wholeModule()).isTrue();
        assertThat(u.refs()).containsExactly("furnace_lib::熔炼");
        assertThat(o.minimalImportSet()).containsExactly("furnace_lib::熔炼");
    }

    @Test
    void wholeImport_partiallyReferenced_onlyKeepsUsedMembers() {
        ImportOptimizer o = optimize("MODULE(\"a\")\nimport furnace_lib;\n"
                + "void f() { furnace_lib.熔炼(); furnace_lib.精炼(); }\nint x = 1;");
        assertThat(o.usedImports()).hasSize(1);
        assertThat(o.minimalImportSet()).containsExactlyInAnyOrder("furnace_lib::熔炼", "furnace_lib::精炼");
    }

    // ===================== 精确 from-import =====================

    @Test
    void fromImport_usedByBareName() {
        ImportOptimizer o = optimize("MODULE(\"a\")\nfrom furnace_lib import 熔炼;\nvoid f() { 熔炼(); }");
        assertThat(o.unusedImports()).isEmpty();
        assertThat(o.usedImports()).hasSize(1);
        assertThat(o.usedImports().get(0).symbol()).isEqualTo("熔炼");
        assertThat(o.minimalImportSet()).containsExactly("熔炼");
    }

    @Test
    void fromImport_unused_pruned() {
        ImportOptimizer o = optimize("MODULE(\"a\")\nfrom furnace_lib import 熔炼;\nvoid f() {}");
        assertThat(o.unusedImports()).hasSize(1);
        assertThat(o.minimalImportSet()).isEmpty();
    }

    @Test
    void fromImport_usedAsNamespacePrefix() {
        // from 模块 import 命名空间；用 命名空间.成员
        ImportOptimizer o = optimize("MODULE(\"a\")\nfrom furnace_lib import 炉;\nvoid f() { 炉.熔炼(); }");
        assertThat(o.usedImports()).hasSize(1);
        assertThat(o.minimalImportSet()).containsExactly("炉::熔炼");
    }

    // ===================== 混合与汇总 =====================

    @Test
    void minimalImportSet_aggregatesMixedImports() {
        ImportOptimizer o = optimize("MODULE(\"a\")\n"
                + "import furnace_lib;\n"
                + "from other_lib import 精炼;\n"
                + "import dead_lib;\n"
                + "void f() { furnace_lib.熔炼(); 精炼(); }");
        assertThat(o.unusedImports()).hasSize(1);          // dead_lib 被砍
        assertThat(o.unusedImports().get(0).module()).isEqualTo("dead_lib");
        assertThat(o.usedImports()).hasSize(2);
        assertThat(o.minimalImportSet())
                .containsExactlyInAnyOrder("furnace_lib::熔炼", "精炼");
    }
}
