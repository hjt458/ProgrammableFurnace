package dev.programmablefurnace.furnace;

import dev.programmablefurnace.machine.SlotNameMap;

/**
 * 可编程熔炉的槽位布局：这台机器唯一的索引定义（纯 JVM，无 MC 依赖，可单测）。
 *
 * <p>此前槽索引有多处手写副本——方块实体的 {@code SLOT_*} 常量、{@link SlotNameMap} 的构造参数、脚本侧的
 * {@code dep[i]}——它们靠人工保持一致，改一处忘一处就会出现"脚本看到的槽"和"实际存物的槽"错位的事故
 * （2026-11-16 修的依赖库偏移 4 槽即此类问题）。现在索引只在这里定义一次：方块实体把常量转发过来，
 * {@code FurnaceEnv} 用 {@link #slotNames(int)} 生成脚本侧映射，单测断言两边一致。</p>
 *
 * <p>布局顺序：输入区 → 燃料 → 输出区 → 依赖库输入 → 依赖库输出 → 拓展槽 → 依赖库容器区。
 * 工作页只有输入/燃料/输出/拓展槽；程序文件从依赖库页的输入槽导入，落在依赖库容器区里执行——
 * 早期版本在输出区之后还有一个"程序槽"，玩家从界面上没有入口（只能靠漏斗塞），2026-11-17 移除。</p>
 */
public final class FurnaceLayout {

    /** 输入槽区 [0..3]：最多 4 路并行烧炼（实际激活几个由 GUI 模式决定）。 */
    public static final int INPUT_COUNT = 4;
    public static final int SLOT_INPUT = 0;

    /** 燃料槽 [4]：一次只烧一份燃料。 */
    public static final int FUEL_COUNT = 1;
    public static final int SLOT_FUEL = SLOT_INPUT + INPUT_COUNT;

    /** 输出槽区 [5..8]：与输入槽一一对应。 */
    public static final int OUTPUT_COUNT = 4;
    public static final int SLOT_OUTPUT = SLOT_FUEL + FUEL_COUNT;

    /** 依赖库的两个出入口槽：[9] 导入、[10] 导出（脚本不可见，只给 GUI 操作）。 */
    public static final int SLOT_DEP_INPUT = SLOT_OUTPUT + OUTPUT_COUNT;
    public static final int SLOT_DEP_OUTPUT = SLOT_DEP_INPUT + 1;

    /** 拓展槽 [11..14]：放 U 盘扩展卡/燃烧优化器，决定工作界面档位；脚本不可见。 */
    public static final int EXTRA_COUNT = 4;
    public static final int EXTRA_START = SLOT_DEP_OUTPUT + 1;

    /** 依赖库容器区起始索引：脚本的 {@code dep[i]} 从这里开始，容量由机器配置决定。 */
    public static final int DEP_START = EXTRA_START + EXTRA_COUNT;

    /** 不含依赖库容量的固定基底槽数。 */
    public static final int BASE_SLOT_COUNT = DEP_START;

    private FurnaceLayout() {
    }

    /** 脚本侧槽名映射：与 {@link #DEP_START} 对齐，gap 是脚本不可见的拓展槽。 */
    public static SlotNameMap slotNames(int depCapacity) {
        return new SlotNameMap(INPUT_COUNT, FUEL_COUNT, OUTPUT_COUNT, 0,
                1, 1, EXTRA_COUNT, depCapacity);
    }

    /** 机器总槽数 = 固定基底 + 依赖库容量。 */
    public static int slotCount(int depCapacity) {
        return BASE_SLOT_COUNT + Math.max(0, depCapacity);
    }

    /** 脚本不可见槽（拓展槽）数量，供 {@link SlotNameMap#gapCount()} 交叉校验。 */
    public static int invisibleSlotCount() {
        return EXTRA_COUNT;
    }

    /** 槽的语义分类（决定 GUI 位置、能否放入、脚本是否可见）。 */
    public enum SlotKind {
        INPUT,
        FUEL,
        OUTPUT,
        EXTRA,
        DEP_INPUT,
        DEP_OUTPUT,
        /** 依赖库容器区（容量由机器配置决定，脚本的 dep[i] 指这里）。 */
        BATCH,
        OUT_OF_RANGE
    }

    /**
     * 把槽索引翻译成语义分类。索引越界（含负数）返回 {@link SlotKind#OUT_OF_RANGE}，不抛异常。
     *
     * @param depCapacity 依赖库容器容量；只有落在 {@code DEP_START..DEP_START+depCapacity-1} 才是 BATCH
     */
    public static SlotKind slotKind(int index, int depCapacity) {
        if (index < 0) return SlotKind.OUT_OF_RANGE;
        if (index < SLOT_FUEL) return SlotKind.INPUT;
        if (index == SLOT_FUEL) return SlotKind.FUEL;
        if (index < SLOT_DEP_INPUT) return SlotKind.OUTPUT;
        if (index == SLOT_DEP_INPUT) return SlotKind.DEP_INPUT;
        if (index == SLOT_DEP_OUTPUT) return SlotKind.DEP_OUTPUT;
        if (index < DEP_START) return SlotKind.EXTRA;
        if (index < DEP_START + Math.max(0, depCapacity)) return SlotKind.BATCH;
        return SlotKind.OUT_OF_RANGE;
    }
}
