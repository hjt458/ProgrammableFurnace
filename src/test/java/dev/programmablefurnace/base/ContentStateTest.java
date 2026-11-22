package dev.programmablefurnace.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ContentState#isRunnable()} 的纯逻辑 UT。
 * <p>"可运行"的口径项目里只有这一处定义：可执行文件与只读可执行能跑，空白/编译失败/依赖文件不能。
 * 依赖 {@code ItemStack} 的 {@code CodeFileItem.holdsRunnableCode} 需要真实物品，归 GameTest。</p>
 */
class ContentStateTest {

    @Test
    void executableIsRunnable() {
        assertThat(ContentState.EXECUTABLE.isRunnable()).isTrue();
    }

    @Test
    void readonlyIsRunnable() {
        // 只读只影响"能不能被重新编译"，不影响"能不能运行"
        assertThat(ContentState.READONLY.isRunnable()).isTrue();
    }

    @Test
    void blankFailedAndDependencyAreNotRunnable() {
        assertThat(ContentState.BLANK.isRunnable()).isFalse();
        assertThat(ContentState.FAILED.isRunnable()).isFalse();
        assertThat(ContentState.DEPENDENCY.isRunnable()).isFalse();
    }

    @Test
    void exactlyTwoStatesAreRunnable() {
        List<ContentState> runnable = Arrays.stream(ContentState.values())
                .filter(ContentState::isRunnable)
                .toList();
        assertThat(runnable).containsExactly(ContentState.EXECUTABLE, ContentState.READONLY);
    }
}
