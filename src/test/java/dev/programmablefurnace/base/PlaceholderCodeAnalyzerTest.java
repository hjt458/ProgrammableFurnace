package dev.programmablefurnace.base;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 占位代码分析器：核心分类逻辑（空→BLANK、首行非空→EXECUTABLE、首行空白→FAILED）。
 * 这是"代码 → 三态"这条业务链的纯逻辑，不依赖 MC 环境，可完整单测。
 */
class PlaceholderCodeAnalyzerTest {

    private final CodeAnalyzer analyzer = new PlaceholderCodeAnalyzer();

    @Test
    void analyze_returnsBlank_forNull() {
        assertThat(analyzer.analyze(null)).isEqualTo(ContentState.BLANK);
    }

    @Test
    void analyze_returnsBlank_forEmpty() {
        assertThat(analyzer.analyze("")).isEqualTo(ContentState.BLANK);
    }

    @Test
    void analyze_returnsFailed_forWhitespaceOnly() {
        // 非空但首行为空白 → 按"首行空白→FAILED"规则判定为失败
        assertThat(analyzer.analyze("   \n  \n")).isEqualTo(ContentState.FAILED);
    }

    @Test
    void analyze_returnsExecutable_whenSingleLineNonBlank() {
        assertThat(analyzer.analyze("set 5")).isEqualTo(ContentState.EXECUTABLE);
    }

    @Test
    void analyze_returnsExecutable_whenFirstLineNonBlank() {
        assertThat(analyzer.analyze("set 5\nadd 3\nprint")).isEqualTo(ContentState.EXECUTABLE);
    }

    @Test
    void analyze_returnsExecutable_whenFirstLineTrimsToNonBlank() {
        // 首行带前导空白但 trim 后非空 → 仍视为可执行
        assertThat(analyzer.analyze("  set 5\nadd")).isEqualTo(ContentState.EXECUTABLE);
    }

    @Test
    void analyze_returnsFailed_whenFirstLineBlankButLaterContent() {
        assertThat(analyzer.analyze("\nadd 3")).isEqualTo(ContentState.FAILED);
    }

    @Test
    void analyze_returnsFailed_whenFirstLineOnlyWhitespace() {
        assertThat(analyzer.analyze("   \nadd 3")).isEqualTo(ContentState.FAILED);
    }
}
