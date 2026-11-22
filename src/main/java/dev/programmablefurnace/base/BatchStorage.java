package dev.programmablefurnace.base;

import net.minecraft.world.item.ItemStack;

/**
 * 批量容器能力接口（模块化）：任何方块实体实现此接口，即获得"输入槽 → 可滚动容器 → 输出槽"的批量管理能力，
 * 供编译器以及后续其他机器基类复用。
 *
 * <p>槽位约定：方块实体的容器被切分为
 * {@code [0..inputCount-1]} 输入区、单个输出槽、以及 {@code batchStartIndex..batchStartIndex+batchCapacity-1} 批量区。
 * 批量区按"最近存入在最前"排序（索引递增 = 存入时间递减），由实现方保证顺序与容量限制。</p>
 */
public interface BatchStorage {

    /** 批量容器起始索引（批量区第一个槽下标）。 */
    int batchStartIndex();

    /** 批量容器容量上限（批量区槽数）。 */
    int batchCapacity();

    /**
     * 把某个输入槽中的物品放入批量容器（push 到最前）。
     *
     * @param inputSlotIndex 输入槽下标
     * @return true 表示放入成功
     */
    boolean moveInputToBatch(int inputSlotIndex);

    /**
     * 把批量容器中指定偏移（相对 batchStartIndex）的条目移动到输出槽。
     *
     * @param batchOffset 批量区内的相对偏移
     * @return true 表示移动成功
     */
    boolean moveBatchToOutput(int batchOffset);

    /**
     * 读取批量容器中指定偏移的条目。
     *
     * @param batchOffset 批量区内的相对偏移
     * @return 该条目的物品（可能为空）
     */
    ItemStack getBatchItem(int batchOffset);

    /**
     * 删除批量容器中指定偏移的条目（内置/可输入文件均可删除）。
     *
     * @param batchOffset 批量区内的相对偏移
     * @return true 表示删除成功
     */
    boolean removeBatchItem(int batchOffset);
}
