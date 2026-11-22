package dev.programmablefurnace.furnace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link FurnaceDataIndex} 的索引表。
 *
 * <p>这些整数是服务端写、客户端读的跨端契约，写错一位的表现是"进度条不动"或"火焰不灭"，
 * 而且不会报错——所以用心跳测试把它们钉住：索引连续、总数与 {@code SimpleContainerData}
 * 的容量一致、进度区间的长度等于输入槽数。</p>
 */
class FurnaceDataIndexTest {

    @Test
    void indices_areContiguousAndStable() {
        assertThat(FurnaceDataIndex.PROGRESS_FIRST).isZero();
        assertThat(FurnaceDataIndex.BURN_TIME).isEqualTo(FurnaceDataIndex.PROGRESS_LAST + 1);
        assertThat(FurnaceDataIndex.RUNNING).isEqualTo(FurnaceDataIndex.BURN_TIME + 1);
        assertThat(FurnaceDataIndex.LIT_DURATION).isEqualTo(FurnaceDataIndex.RUNNING + 1);
        assertThat(FurnaceDataIndex.GUI_MODE).isEqualTo(FurnaceDataIndex.LIT_DURATION + 1);
        assertThat(FurnaceDataIndex.BURN_MAX_PROGRESS).isEqualTo(FurnaceDataIndex.GUI_MODE + 1);
        assertThat(FurnaceDataIndex.COUNT).isEqualTo(FurnaceDataIndex.BURN_MAX_PROGRESS + 1);
    }

    @Test
    void progressRange_matchesInputSlotCount() {
        int slots = FurnaceLayout.INPUT_COUNT;

        assertThat(FurnaceDataIndex.PROGRESS_LAST - FurnaceDataIndex.PROGRESS_FIRST + 1).isEqualTo(slots);
        for (int slot = 0; slot < slots; slot++) {
            assertThat(FurnaceDataIndex.progress(slot)).isEqualTo(FurnaceDataIndex.PROGRESS_FIRST + slot);
            assertThat(FurnaceDataIndex.isProgress(FurnaceDataIndex.progress(slot))).isTrue();
        }
    }

    @Test
    void progress_clampsOutOfRangeSlots() {
        assertThat(FurnaceDataIndex.progress(-5)).isEqualTo(FurnaceDataIndex.PROGRESS_FIRST);
        assertThat(FurnaceDataIndex.progress(99)).isEqualTo(FurnaceDataIndex.PROGRESS_LAST);
    }

    @Test
    void isProgress_rejectsDataSlotsOutsideTheRange() {
        assertThat(FurnaceDataIndex.isProgress(FurnaceDataIndex.BURN_TIME)).isFalse();
        assertThat(FurnaceDataIndex.isProgress(FurnaceDataIndex.GUI_MODE)).isFalse();
        assertThat(FurnaceDataIndex.isProgress(-1)).isFalse();
        assertThat(FurnaceDataIndex.isProgress(FurnaceDataIndex.COUNT)).isFalse();
    }
}
