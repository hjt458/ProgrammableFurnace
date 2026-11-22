package dev.programmablefurnace.machine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link SlotNameMap} 槽组映射的单测（纯 JVM，无 MC 依赖）。
 * <p>重点锁死 gap 契约：熔炉在 dep_output 之后还有 4 个 U 盘拓展槽（{@code EXTRA_COUNT}），
 * 映射必须跳过它们，否则脚本的 {@code dep[i]} 会落到拓展槽上（2026-11-16 修掉的 P0：
 * 脚本能读走 U 盘卡、真实依赖库末 4 槽不可达）。</p>
 */
class SlotNameMapTest {

    /** 与 ProgrammableFurnaceBlockEntity 的真实布局一致：4 输入 / 1 燃料 / 4 输出 / 1 程序 / 1+1 依赖库 in·out / 4 拓展槽 / 27 容器。 */
    private static SlotNameMap furnaceLayout() {
        return new SlotNameMap(4, 1, 4, 1, 1, 1, 4, 27);
    }

    @Test
    void furnaceDepStartMatchesBlockEntityLayout() {
        // 必须等于 ProgrammableFurnaceBlockEntity.DEP_START=16
        assertThat(furnaceLayout().depStart()).isEqualTo(16);
    }

    @Test
    void groupsFollowSlotOrder() {
        SlotNameMap map = furnaceLayout();
        assertThat(map.group("input")).containsExactly(0, 1, 2, 3);
        assertThat(map.group("fuel")).containsExactly(4);
        assertThat(map.group("output")).containsExactly(5, 6, 7, 8);
        assertThat(map.group("program")).containsExactly(9);
        assertThat(map.group("dep_input")).containsExactly(10);
        assertThat(map.group("dep_output")).containsExactly(11);
    }

    @Test
    void depContainerStartsAfterGap() {
        SlotNameMap map = furnaceLayout();
        assertThat(map.gapCount()).isEqualTo(4);
        assertThat(map.depCapacity()).isEqualTo(27);
        assertThat(map.group("dep")).hasSize(27).startsWith(16).endsWith(42);
        assertThat(map.singleIndex("dep0")).isEqualTo(16);
        assertThat(map.singleIndex("dep26")).isEqualTo(42);
        assertThat(map.singleIndex("dep27")).isNull();
    }

    @Test
    void gapSlotsAreNotExposedAsAGroup() {
        SlotNameMap map = furnaceLayout();
        // 拓展槽（12-15）刻意不属于任何脚本槽组
        assertThat(map.group("extra")).isEmpty();
        assertThat(map.singleIndex("extra")).isNull();
        assertThat(map.group("dep")).doesNotContain(12, 13, 14, 15);
    }

    @Test
    void zeroGapFallsBackToPlainSum() {
        // gap=0 即"三组相加就是容器起点"的旧行为，说明 gap 是唯一变量
        assertThat(new SlotNameMap(1, 1, 1, 1, 1, 1, 0, 10).depStart()).isEqualTo(6);
    }

    @Test
    void namesAreCaseInsensitiveAndUnknownIsEmpty() {
        SlotNameMap map = furnaceLayout();
        assertThat(map.group("Input")).containsExactly(0, 1, 2, 3);
        assertThat(map.groupSize("INPUT")).isEqualTo(4);
        assertThat(map.groupSize("dep")).isEqualTo(27);
        assertThat(map.group("nope")).isEmpty();
        assertThat(map.groupSize("nope")).isZero();
    }
}
