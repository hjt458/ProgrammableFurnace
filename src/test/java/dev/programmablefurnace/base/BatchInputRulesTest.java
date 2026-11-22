package dev.programmablefurnace.base;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器依赖库输入规则的纯逻辑 UT（覆盖 {@link BatchInputRules#dependencyInsertIndex}）。
 * 规则：只能可执行/依赖文件；可执行文件唯一；可执行排最上、依赖追加末尾。
 * 编译器不执行执行函数，故不适用本规则（可放多个可执行），由 {@code CompilerBlockEntity.moveInputToBatch}
 * 的 push-front 实现体现，不在本测试范围。
 */
class BatchInputRulesTest {

    private static final int CAP = 5;

    // ===== 类型限制：只能可执行文件与依赖库文件 =====

    @Test
    void blankFileRejected() {
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.BLANK, 0, CAP, false)).isEqualTo(-1);
    }

    @Test
    void failedFileRejected() {
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.FAILED, 0, CAP, false)).isEqualTo(-1);
    }

    @Test
    void executableAllowed() {
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.EXECUTABLE, 0, CAP, false)).isEqualTo(0);
    }

    @Test
    void readonlyAllowedLikeExecutable() {
        // 只读可执行同可执行：可进、排最上
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.READONLY, 0, CAP, false)).isEqualTo(0);
    }

    @Test
    void dependencyAllowed() {
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.DEPENDENCY, 0, CAP, false)).isEqualTo(0);
    }

    // ===== 可执行唯一：容器已有可执行文件时再输可执行拒绝 =====

    @Test
    void executableRejectedWhenOneAlreadyPresent() {
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.EXECUTABLE, 1, CAP, true)).isEqualTo(-1);
    }

    @Test
    void readonlyRejectedWhenExecutablePresent() {
        // 只读可执行同可执行：容器已有可执行时拒绝（执行器同时只跑一个程序）
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.READONLY, 1, CAP, true)).isEqualTo(-1);
    }

    @Test
    void dependencyAllowedEvenWhenExecutablePresent() {
        // 已有 1 个可执行（占用1），依赖文件仍可追加到末尾
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.DEPENDENCY, 1, CAP, true)).isEqualTo(1);
    }

    // ===== 排序：可执行排最上(0)，依赖追加到末尾 =====

    @Test
    void executableAlwaysInsertsAtTop() {
        // 容器已有若干依赖（占用3），新可执行文件仍插最上 0
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.EXECUTABLE, 3, CAP, false)).isEqualTo(0);
    }

    @Test
    void dependencyAppendsToEnd() {
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.DEPENDENCY, 2, CAP, false)).isEqualTo(2);
    }

    @Test
    void dependencyAppendsAfterExistingDependencies() {
        // 已有 1 可执行(0) + 2 依赖(1,2)，新依赖追加到末尾 3
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.DEPENDENCY, 3, CAP, true)).isEqualTo(3);
    }

    // ===== 容量满 =====

    @Test
    void anyInputRejectedWhenFull() {
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.EXECUTABLE, CAP, CAP, false)).isEqualTo(-1);
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.DEPENDENCY, CAP, CAP, false)).isEqualTo(-1);
        assertThat(BatchInputRules.dependencyInsertIndex(ContentState.READONLY, CAP, CAP, false)).isEqualTo(-1);
    }

    // ===== 口径一致性：规则认的"可执行"必须就是 ContentState.isRunnable() =====

    @Test
    void acceptedStatesMatchIsRunnablePlusDependency() {
        // 防漂移：规则里"能进容器"的集合 = 可运行状态 ∪ {依赖文件}。两处口径一旦分叉，
        // 就会出现"文件进得去但机器不执行"或"能跑却放不进去"，靠这条测试提前炸掉。
        for (ContentState state : ContentState.values()) {
            boolean accepted = BatchInputRules.dependencyInsertIndex(state, 0, CAP, false) >= 0;
            assertThat(accepted)
                    .as("状态 %s 的收件结果应与 isRunnable()/DEPENDENCY 一致", state)
                    .isEqualTo(state.isRunnable() || state == ContentState.DEPENDENCY);
        }
    }
}
