package dev.programmablefurnace.furnace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.programmablefurnace.machine.SlotNameMap;

/**
 * 槽位布局单一真源的回归测试：{@link FurnaceLayout} 的索引与 {@link SlotNameMap} 的槽名映射必须彼此对齐。
 * <p>这两份东西以前各写一遍（方块实体常量 + FurnaceEnv 构造参数 + SlotNameMap 顺序表），改一处忘一处就会
 * 让脚本的 {@code dep[i]} 落到 U 盘拓展槽上。本测试把"对齐"这件事固定下来：一旦谁改了布局，这里先红。</p>
 */
class FurnaceLayoutTest {

    @Test
    void baseIndices_areContiguousAndStable() {
        assertThat(FurnaceLayout.SLOT_INPUT).isEqualTo(0);
        assertThat(FurnaceLayout.INPUT_COUNT).isEqualTo(4);
        assertThat(FurnaceLayout.SLOT_FUEL).isEqualTo(4);
        assertThat(FurnaceLayout.FUEL_COUNT).isEqualTo(1);
        assertThat(FurnaceLayout.SLOT_OUTPUT).isEqualTo(5);
        assertThat(FurnaceLayout.OUTPUT_COUNT).isEqualTo(4);
        assertThat(FurnaceLayout.SLOT_DEP_INPUT).isEqualTo(9);
        assertThat(FurnaceLayout.SLOT_DEP_OUTPUT).isEqualTo(10);
        assertThat(FurnaceLayout.EXTRA_START).isEqualTo(11);
        assertThat(FurnaceLayout.EXTRA_COUNT).isEqualTo(4);
        assertThat(FurnaceLayout.DEP_START).isEqualTo(15);
        assertThat(FurnaceLayout.BASE_SLOT_COUNT).isEqualTo(FurnaceLayout.DEP_START);
    }

    @Test
    void slotNames_alignWithMachineLayout() {
        SlotNameMap map = FurnaceLayout.slotNames(27);

        assertThat(map.depStart()).isEqualTo(FurnaceLayout.DEP_START);
        assertThat(map.depCapacity()).isEqualTo(27);
        // 拓展槽夹在 dep_output 与依赖库容器之间，脚本不可见
        assertThat(map.gapCount()).isEqualTo(FurnaceLayout.EXTRA_COUNT);

        assertThat(map.group("input")).containsExactly(0, 1, 2, 3);
        assertThat(map.group("fuel")).containsExactly(4);
        assertThat(map.group("output")).containsExactly(5, 6, 7, 8);
        assertThat(map.group("dep_input")).containsExactly(FurnaceLayout.SLOT_DEP_INPUT);
        assertThat(map.group("dep_output")).containsExactly(FurnaceLayout.SLOT_DEP_OUTPUT);
        // 脚本 dep[0] 必须是真实依赖库容器首槽，不能是拓展槽
        assertThat(map.singleIndex("dep0")).isEqualTo(FurnaceLayout.DEP_START);
        // 程序槽已移除：工作页不再有放程序的格子，程序只从依赖库页导入
        assertThat(map.group("program")).isEmpty();
    }

    @Test
    void slotNames_neverPointScriptAtInvisibleSlots() {
        SlotNameMap map = FurnaceLayout.slotNames(27);
        List<Integer> dep = map.group("dep");

        int extraStart = FurnaceLayout.EXTRA_START;
        int extraEnd = FurnaceLayout.EXTRA_START + FurnaceLayout.EXTRA_COUNT;
        for (int i = extraStart; i < extraEnd; i++) {
            assertThat(dep).as("dep 不应包含拓展槽 %s", i).doesNotContain(i);
            assertThat(map.group("input")).doesNotContain(i);
            assertThat(map.group("output")).doesNotContain(i);
        }
        assertThat(dep).hasSize(27).startsWith(FurnaceLayout.DEP_START);
    }

    @Test
    void slotCount_addsCapacityToBase() {
        assertThat(FurnaceLayout.slotCount(27)).isEqualTo(42);
        assertThat(FurnaceLayout.slotCount(0)).isEqualTo(FurnaceLayout.BASE_SLOT_COUNT);
        assertThat(FurnaceLayout.slotCount(-5)).isEqualTo(FurnaceLayout.BASE_SLOT_COUNT);
    }

    @Test
    void baseSlots_areCoveredByGroupsOrTheInvisibleGap() {
        SlotNameMap map = FurnaceLayout.slotNames(27);
        boolean[] covered = new boolean[FurnaceLayout.DEP_START];
        for (String group : new String[]{"input", "fuel", "output", "program", "dep_input", "dep_output"}) {
            for (int index : map.group(group)) {
                assertThat(index).isBetween(0, FurnaceLayout.DEP_START - 1);
                covered[index] = true;
            }
        }
        int uncovered = 0;
        for (boolean c : covered) {
            if (!c) uncovered++;
        }
        // 没被任何槽组覆盖的基底槽只能是拓展槽（脚本不可见）
        assertThat(uncovered).isEqualTo(FurnaceLayout.invisibleSlotCount());
    }

    @Test
    void slotKind_mapsEveryBaseSlotToItsSemanticRole() {
        int capacity = 27;
        assertThat(FurnaceLayout.slotKind(0, capacity)).isEqualTo(FurnaceLayout.SlotKind.INPUT);
        assertThat(FurnaceLayout.slotKind(3, capacity)).isEqualTo(FurnaceLayout.SlotKind.INPUT);
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.SLOT_FUEL, capacity)).isEqualTo(FurnaceLayout.SlotKind.FUEL);
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.SLOT_OUTPUT, capacity)).isEqualTo(FurnaceLayout.SlotKind.OUTPUT);
        assertThat(FurnaceLayout.slotKind(8, capacity)).isEqualTo(FurnaceLayout.SlotKind.OUTPUT);
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.SLOT_DEP_INPUT, capacity)).isEqualTo(FurnaceLayout.SlotKind.DEP_INPUT);
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.SLOT_DEP_OUTPUT, capacity)).isEqualTo(FurnaceLayout.SlotKind.DEP_OUTPUT);
        for (int i = 0; i < FurnaceLayout.EXTRA_COUNT; i++) {
            assertThat(FurnaceLayout.slotKind(FurnaceLayout.EXTRA_START + i, capacity)).isEqualTo(FurnaceLayout.SlotKind.EXTRA);
        }
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.DEP_START, capacity)).isEqualTo(FurnaceLayout.SlotKind.BATCH);
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.DEP_START + capacity - 1, capacity))
                .isEqualTo(FurnaceLayout.SlotKind.BATCH);
    }

    @Test
    void slotKind_isOutOfRangeOutsideTheContainerAndNeverThrows() {
        int capacity = 27;
        assertThat(FurnaceLayout.slotKind(-1, capacity)).isEqualTo(FurnaceLayout.SlotKind.OUT_OF_RANGE);
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.slotCount(capacity), capacity))
                .isEqualTo(FurnaceLayout.SlotKind.OUT_OF_RANGE);
        // 容量 0 时依赖库区不存在，DEP_START 本身就已经越界
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.DEP_START, 0)).isEqualTo(FurnaceLayout.SlotKind.OUT_OF_RANGE);
        assertThat(FurnaceLayout.slotKind(FurnaceLayout.DEP_START, -5)).isEqualTo(FurnaceLayout.SlotKind.OUT_OF_RANGE);
    }
}
