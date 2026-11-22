package dev.programmablefurnace.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 单元测试示例：测纯逻辑工具 {@link TextUtil}。
 * <p>这类不依赖 Minecraft 运行环境的方法，直接用 JUnit 5 + AssertJ 跑（{@code gradlew test}）。</p>
 */
class TextUtilTest {

    // ---- firstLine ----

    @Test
    void firstLine_returnsWholeString_whenNoNewline() {
        assertThat(TextUtil.firstLine("just one line")).isEqualTo("just one line");
    }

    @Test
    void firstLine_stopsAtFirstNewline() {
        assertThat(TextUtil.firstLine("line1\nline2\nline3")).isEqualTo("line1");
    }

    @Test
    void firstLine_returnsEmpty_whenNull() {
        assertThat(TextUtil.firstLine(null)).isEmpty();
    }

    @Test
    void firstLine_returnsEmpty_whenEmpty() {
        assertThat(TextUtil.firstLine("")).isEmpty();
    }

    @Test
    void firstLine_handlesCrLf() {
        assertThat(TextUtil.firstLine("a\r\nb")).isEqualTo("a");
    }
}
