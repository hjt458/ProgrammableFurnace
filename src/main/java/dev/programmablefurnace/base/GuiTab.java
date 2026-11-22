package dev.programmablefurnace.base;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用页签切换模块（模块化）：
 * 在容器 GUI（一层界面）上渲染一排页签，点击某个页签即触发宿主提供的切换回调。
 *
 * <p><b>关键设计：不重建 Screen。</b>切换行为由宿主通过 {@link #addTab} 注入的 Runnable 决定
 * （例如只切换本 Screen 内部的渲染模式），而非调用 setScreenAndShow 重建实例——避免切换时
 * 背景蒙版重绘闪烁。任何机器的 GUI 都能复用。</p>
 *
 * <p>用法：构造时设置页签位置/尺寸，addTab 声明页签标题 + 切换回调；在宿主的 init 里
 * {@link #initButtons} 记录页签矩形，在 {@link #render} 里自绘，并让宿主覆盖 mouseClicked
 * 调用 {@link #handleClick} 处理点击。</p>
 */
public class GuiTab {

    private final List<GuiTabLogic.Tab> tabs = new ArrayList<>();
    private final List<Component> tabTitles = new ArrayList<>(); // 渲染用（支持本地化）
    private final List<int[]> tabRects = new ArrayList<>(); // {x,y,w,h} 屏幕绝对坐标
    private int activeIndex = 0;
    private int tabX;
    private int tabY;
    private int tabW = 46;   // 页签宽（横）
    private int tabH = 18;   // 页签高（纵）
    private int spacing = 2;

    public GuiTab(int tabX, int tabY) {
        this.tabX = tabX;
        this.tabY = tabY;
    }

    /** 设置页签尺寸（宽、高）。 */
    public GuiTab setTabSize(int width, int height) {
        this.tabW = width;
        this.tabH = height;
        return this;
    }

    /** 追加一个页签（切换动作由宿主提供）。标题用 {@link Component} 以支持语言文件本地化。 */
    public GuiTab addTab(Component title, Runnable switchAction) {
        this.tabs.add(new GuiTabLogic.Tab("", switchAction)); // title 仅渲染用，存于 tabTitles
        this.tabTitles.add(title);
        return this;
    }

    /** 清空所有页签（宿主在每次 init 重建页签时调用，避免重复追加）。 */
    public GuiTab clearTabs() {
        this.tabs.clear();
        this.tabTitles.clear();
        this.tabRects.clear();
        this.activeIndex = 0;
        return this;
    }

    /** 设置当前激活页签。 */
    public GuiTab setActive(int index) {
        this.activeIndex = Math.max(0, Math.min(index, this.tabs.size() - 1));
        return this;
    }

    public int getActiveIndex() {
        return this.activeIndex;
    }

    /**
     * 记录页签矩形（左侧竖排，在宿主 Screen 的 init 调用）。
     *
     * @param host    宿主 Screen
     * @param leftPos 宿主容器 GUI 的 leftPos
     * @param topPos  宿主容器 GUI 的 topPos
     */
    public void initButtons(Screen host, int leftPos, int topPos) {
        this.tabRects.clear();
        // 左侧一列：x 固定（相对 leftPos），y 从上往下逐行
        int y = this.tabY;
        for (int i = 0; i < this.tabs.size(); i++) {
            this.tabRects.add(new int[]{leftPos + this.tabX, topPos + y, this.tabW, this.tabH});
            y += this.tabH + this.spacing;
        }
    }

    /** 处理点击：命中某页签则调用其切换回调。由宿主 mouseClicked 转发。命中判定委托给纯逻辑 {@link GuiTabLogic}。 */
    public void handleClick(Screen host, MouseButtonEvent event) {
        GuiTabLogic.dispatchClick(this.tabs, this.tabRects, this.activeIndex, event.x(), event.y());
    }

    /** 渲染页签栏：激活页签用亮色底，非激活用深灰。标题从 tabTitles（Component，本地化）渲染。
     * 背景宽度自适应文字宽度（取页签配置宽与文字宽+边距的较大者），但受限于页签左边缘
     * （背景最多延伸到 GUI 左边缘，不越过、不遮挡主要 GUI 界面）；文字超宽时左对齐防右溢出。 */
    public void render(GuiGraphicsExtractor graphics, Font font, int leftPos, int topPos) {
        for (int i = 0; i < this.tabs.size(); i++) {
            int[] r = this.tabRects.get(i);
            boolean active = i == this.activeIndex;
            int bgColor = active ? 0xFF4A6B8A : 0xFF2C3440;
            int fgColor = active ? 0xFFFFFFFF : 0xFFB0B8C0;
            Component title = this.tabTitles.get(i);
            int tw = font.width(title);
            int maxW = -this.tabX;                 // 背景右边缘不超过 GUI 左边缘（不遮挡主界面）
            int bw = Math.max(r[2], tw + 4);
            if (maxW > 0) bw = Math.min(bw, maxW);
            graphics.fill(r[0], r[1], r[0] + bw, r[1] + r[3], bgColor);
            int tx = r[0] + (bw - tw) / 2;
            if (tx + tw > r[0] + bw) tx = r[0] + bw - tw; // 文字超背景时左对齐防右溢
            graphics.text(font, title, tx, r[1] + (r[3] - 8) / 2, fgColor);
        }
    }
}
