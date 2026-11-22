package dev.programmablefurnace.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * FileType 枚举：BEGIN/END 为占位边界，真实类型只有 DEPENDENCY(依赖库,不可输出) 与 RUNTIME(运行库,可输出)。
 * next() 用于 {@code for (t = BEGIN; t != END; t = t.next())} 的范围遍历。
 */
class FileTypeTest {

    @Test
    void realTypes_areOnlyDependencyAndRuntime_betweenPlaceholders() {
        assertThat(FileType.values()).containsExactly(
                FileType.BEGIN, FileType.DEPENDENCY, FileType.RUNTIME, FileType.END);
    }

    @Test
    void next_advancesFromBeginThroughRealTypesToEnd() {
        assertThat(FileType.BEGIN.next()).isEqualTo(FileType.DEPENDENCY);
        assertThat(FileType.DEPENDENCY.next()).isEqualTo(FileType.RUNTIME);
        assertThat(FileType.RUNTIME.next()).isEqualTo(FileType.END);
    }

    @Test
    void next_onEnd_throwsOutOfBounds_byDesign() {
        // 约定遍历终止条件是 t != END，因此不会对 END 调 next()；
        // 这里仅验证越界行为，防止以后有人误用。
        assertThatThrownBy(FileType.END::next)
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }
}
