package dev.programmablefurnace.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * GuiTab 页签命中逻辑（{@link GuiTabLogic#dispatchClick}）。
 * 纯 JVM 可测的核心：命中哪个页签、是否触发切换回调，不依赖 MC 输入事件类。
 */
class GuiTabTest {

    /** 两个页签矩形：tab0={100,100,50,20}，tab1={100,120,50,20}。 */
    private static List<int[]> rects() {
        return List.of(new int[]{100, 100, 50, 20}, new int[]{100, 120, 50, 20});
    }

    @Test
    void dispatchClick_hitsInactiveTab_andRunsItsAction() {
        AtomicInteger ran = new AtomicInteger();
        List<GuiTabLogic.Tab> tabs = List.of(
                new GuiTabLogic.Tab("A", () -> {}),
                new GuiTabLogic.Tab("B", ran::incrementAndGet));
        // 命中 tab1（y 120~140 区间）
        assertThat(GuiTabLogic.dispatchClick(tabs, rects(), 0, 110, 125)).isTrue();
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void dispatchClick_hittingActiveTab_doesNotRunAnyAction() {
        AtomicInteger ran = new AtomicInteger();
        List<GuiTabLogic.Tab> tabs = List.of(
                new GuiTabLogic.Tab("A", ran::incrementAndGet),
                new GuiTabLogic.Tab("B", () -> {}));
        // 命中 activeIndex=0（y 100~120 区间），应不触发
        assertThat(GuiTabLogic.dispatchClick(tabs, rects(), 0, 110, 105)).isTrue();
        assertThat(ran.get()).isZero();
    }

    @Test
    void dispatchClick_miss_returnsFalse() {
        List<GuiTabLogic.Tab> tabs = List.of(
                new GuiTabLogic.Tab("A", () -> {}),
                new GuiTabLogic.Tab("B", () -> {}));
        assertThat(GuiTabLogic.dispatchClick(tabs, rects(), 0, 5, 5)).isFalse();
    }

    @Test
    void dispatchClick_boundaries_areInclusive() {
        List<GuiTabLogic.Tab> tabs = List.of(new GuiTabLogic.Tab("A", () -> {}));
        List<int[]> one = List.of(new int[]{100, 100, 50, 20});
        // 右边界 x=150、下边界 y=120 属于闭区间 → 命中
        assertThat(GuiTabLogic.dispatchClick(tabs, one, 0, 150, 120)).isTrue();
        // 刚超出右边界 x=151 → 未命中
        assertThat(GuiTabLogic.dispatchClick(tabs, one, 0, 151, 110)).isFalse();
        // 刚超出下边界 y=121 → 未命中
        assertThat(GuiTabLogic.dispatchClick(tabs, one, 0, 110, 121)).isFalse();
    }
}
