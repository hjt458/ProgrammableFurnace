package dev.programmablefurnace.base;

import java.util.List;

/**
 * 页签点击的纯逻辑（不依赖任何 MC 类，可纯 JVM 单测）。
 * {@link GuiTab} 在 GUI 层负责布局与渲染，命中判定与回调触发委托给本类的 {@link #dispatchClick}。
 */
public final class GuiTabLogic {

    /** 一个页签：标题 + 切换回调（宿主决定切换后做什么）。 */
    public record Tab(String title, Runnable switchAction) {}

    private GuiTabLogic() {}

    /**
     * 在矩形列表中查找命中项，命中"未激活"页签时执行其切换动作。
     *
     * @param rects 每个页签的 {x,y,w,h}（屏幕绝对坐标）
     * @param activeIndex 当前激活页签下标（命中它不触发切换）
     * @return true 表示命中了某个页签
     */
    public static boolean dispatchClick(List<Tab> tabs, List<int[]> rects, int activeIndex, double mx, double my) {
        for (int i = 0; i < rects.size(); i++) {
            int[] r = rects.get(i);
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                if (i != activeIndex) {
                    tabs.get(i).switchAction().run();
                }
                return true;
            }
        }
        return false;
    }
}
