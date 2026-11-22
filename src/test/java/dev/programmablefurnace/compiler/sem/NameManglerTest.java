package dev.programmablefurnace.compiler.sem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 名称修饰 / 转义（草稿 §2.2 ③）测试：函数 / 变量 / RunLoop 入口的确定性修饰名。 */
class NameManglerTest {

    @Test
    void mangleFunc_rootScope() {
        assertThat(NameMangler.mangleFunc("smelting", "<module>", "熔炼", List.of("int", "int")))
                .isEqualTo("PF/smelting/熔炼(int,int)");
    }

    @Test
    void mangleFunc_namespaceScope() {
        assertThat(NameMangler.mangleFunc("smelting", "炉", "设定", List.of()))
                .isEqualTo("PF/smelting/炉/设定()");
    }

    @Test
    void mangleFunc_nestedNamespace() {
        assertThat(NameMangler.mangleFunc("a", "外层::内层", "f", List.of("vector<Item>")))
                .isEqualTo("PF/a/外层/内层/f(vector<Item>)");
    }

    @Test
    void mangleVar() {
        assertThat(NameMangler.mangleVar("smelting", "<module>", "粗铁流"))
                .isEqualTo("PF/smelting/粗铁流");
    }

    @Test
    void overload_sameNameDifferentParams_distinguished() {
        String a = NameMangler.mangleFunc("m", "<module>", "熔炼", List.of("int"));
        String b = NameMangler.mangleFunc("m", "<module>", "熔炼", List.of("int", "int"));
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo("PF/m/熔炼(int)");
    }

    @Test
    void entryName_fixed() {
        assertThat(NameMangler.entryName("smelting")).isEqualTo("PF/ENTRY/smelting");
    }

    @Test
    void specialCharsInModule_sanitized() {
        assertThat(NameMangler.mangleVar("my lib-v2", "<module>", "x"))
                .isEqualTo("PF/my_lib_v2/x");
    }
}
