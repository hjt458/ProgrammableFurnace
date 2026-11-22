package dev.programmablefurnace.furnace;

/**
 * 熔炉 {@code ContainerData} 的索引表（GUI 同步用）。
 *
 * <p>为什么要有这张表：这些整数既出现在方块实体的 {@code get/set}，又出现在菜单的
 * {@code getDataValue(...)}，还出现在界面的绘制代码里。以前三处各写各的字面量，注释甚至写错
 * （"index4 = guiMode"），结果 {@code FrozenGuiModeData} 冻结的是 7 号槽、注释却说 4 号，
 * 谁读谁迷糊。索引是跨端契约（服务端写入、客户端读取），必须是**同一个常量**。</p>
 *
 * <p>唯一真源：{@code ProgrammableFurnaceBlockEntity.getCount()} 返回 {@link #COUNT}，
 * 新增字段时改这里 + 方块实体的 {@code get/set} + 菜单/界面里的读取点。</p>
 */
public final class FurnaceDataIndex {

    /** 每个输入槽的进度占一个索引，按槽序连续排列（槽 i 的进度 = {@code PROGRESS_FIRST + i}）。 */
    public static final int PROGRESS_FIRST = 0;
    /** 最后一个输入槽进度（含）。输入槽上限是 {@code FurnaceLayout.INPUT_COUNT}，那边改了这里也要改。 */
    public static final int PROGRESS_LAST = 3;
    /** 当前燃料剩余燃烧 tick。 */
    public static final int BURN_TIME = 4;
    /** 是否运行中（0/1）。 */
    public static final int RUNNING = 5;
    /** 本次点火的燃烧总时长（火焰贴图按 burn/lit 计算高度）。 */
    public static final int LIT_DURATION = 6;
    /** 当前界面模式（111/212/313/414），决定用哪张背景图。 */
    public static final int GUI_MODE = 7;
    /** 本炉子允许的最大进度（来自燃料/优化器配置，决定进度条满格位置）。 */
    public static final int BURN_MAX_PROGRESS = 8;
    /** 索引总数，直接传给 {@code SimpleContainerData} 与 {@code getCount()}。 */
    public static final int COUNT = 9;

    private FurnaceDataIndex() {}

    /** 输入槽 index → 该槽进度的 ContainerData 索引。 */
    public static int progress(int slot) {
        return PROGRESS_FIRST + Math.max(0, Math.min(PROGRESS_LAST - PROGRESS_FIRST, slot));
    }

    /** 是否为"输入槽进度"区间的索引。 */
    public static boolean isProgress(int index) {
        return index >= PROGRESS_FIRST && index <= PROGRESS_LAST;
    }
}
