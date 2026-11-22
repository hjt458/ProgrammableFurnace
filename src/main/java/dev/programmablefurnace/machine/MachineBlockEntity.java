package dev.programmablefurnace.machine;

import dev.programmablefurnace.base.BatchStorage;
import dev.programmablefurnace.base.MachineConfigs;
import dev.programmablefurnace.base.UIModeSwitcher;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 双界面机器方块实体基类（工厂模式）。编译器/执行器/未来机器共用：
 * <ul>
 *   <li>依赖库能力：实现 {@link BatchStorage} 的通用部分（getBatchItem / batchCapacity / 批量→输出 / 删除），
 *       仅"输入"规则由子类 {@link #moveInputToBatch} 实现（编译器 push-front 可多个可执行；
 *       执行器可执行唯一且最上）；</li>
 *   <li>双界面：{@link UIModeSwitcher} 默认只切生效套（不迁移），需要切换迁移的机器（如编译器）覆写；</li>
 *   <li>槽位布局：{@code [0..baseSlotCount-1]} 基础槽（工作界面槽 + 依赖库 in/out），
 *       {@code [batchStart..batchStart+capacity-1]} 依赖库容器；</li>
 *   <li>持久化：inventory 通用序列化（子类额外字段覆写 save/load 并调 super）。</li>
 * </ul>
 * 子类需实现：{@link #getBaseSlotCount} / {@link #getWorkInputIndex} / {@link #getDepInputIndex} /
 * {@link #getDepOutputIndex} / {@link #getBatchStart} / {@link #getMachineConfig} / {@link #moveInputToBatch} /
 * {@link #getDisplayName} / {@link #createMenu}。
 */
public abstract class MachineBlockEntity extends BlockEntity
        implements Container, MenuProvider, BatchStorage, UIModeSwitcher {

    protected final ItemStack[] items;
    protected int uiMode = 0;
    /** 基础槽数（工作界面槽 + 依赖库 in/out），构造期确定，之后不变。 */
    private final int baseSlotCount;
    /** 依赖库容器槽数，构造期从配置取；配置改了重开游戏即生效。 */
    private final int batchCapacity;

    /**
     * @param baseSlotCount 基础槽数（工作界面槽 + 依赖库 in/out）
     * @param batchCapacity 依赖库容器槽数
     *
     * <p>这两个值必须由子类从**常量/静态配置**算好后传进来，不能在构造器里回调子类方法：
     * 父类构造器执行时子类字段还是默认值（0/null），回调 {@code getBaseSlotCount()} 这类钩子
     * 拿到的是错的值，而错的是数组长度——要么少一格要么 NPE，且只在特定机器上偶发。</p>
     */
    protected MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                 int baseSlotCount, int batchCapacity) {
        super(type, pos, state);
        this.baseSlotCount = Math.max(0, baseSlotCount);
        this.batchCapacity = Math.max(0, batchCapacity);
        this.items = new ItemStack[this.baseSlotCount + this.batchCapacity];
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemStack.EMPTY;
        }
    }

    // ===================== 子类钩子 =====================

    /** 基础槽总数（工作界面槽 + 依赖库 in/out），依赖库容器槽在其后。构造期已确定。 */
    protected final int getBaseSlotCount() {
        return baseSlotCount;
    }

    /** 工作界面输入槽索引（quickMove 移入目标，uiMode==0 生效）。 */
    protected abstract int getWorkInputIndex();

    /** 依赖库界面输入槽索引。 */
    protected abstract int getDepInputIndex();

    /** 依赖库界面输出槽索引。 */
    protected abstract int getDepOutputIndex();

    /** 依赖库容器起始索引。 */
    protected abstract int getBatchStart();

    /** 本机器的配置（容量 / 输出权限），来自 MachineConfigs。 */
    protected abstract MachineConfigs.Machine getMachineConfig();

    // ===================== UI 模式 =====================

    public int uiMode() { return uiMode; }

    public void setUiMode(int m) { this.uiMode = m; }

    // 供 Screen/Menu 等跨包访问的公共访问器
    public int depInputIndex() { return getDepInputIndex(); }
    public int depOutputIndex() { return getDepOutputIndex(); }
    public MachineConfigs.Machine machineConfig() { return getMachineConfig(); }

    /** 默认：只切换生效界面套，不迁移物品。需迁移的机器（编译器）覆写。 */
    @Override
    public boolean applySwitchMode(int newMode) {
        if (newMode == this.uiMode) return false;
        this.uiMode = newMode;
        this.setChanged();
        return true;
    }

    // ===================== BatchStorage（依赖库）=====================

    @Override
    public int batchStartIndex() { return getBatchStart(); }

    /**
     * 依赖库容器槽数 = 构造期从配置读到的值。
     *
     * <p>不再每次 {@code getMachineConfig().capacity()} 现算：容量决定 {@code items} 数组长度，
     * 若配置在运行期变化（热加载），现算的值会超过数组长度，越界异常会出现在很远的调用栈里。
     * 容量要变就重新加载世界（并在 {@code save/load} 迁移），不允许"同一实例里数组长 27 而容量说 54"。</p>
     */
    @Override
    public int batchCapacity() { return batchCapacity; }

    @Override
    public ItemStack getBatchItem(int batchOffset) {
        return items[getBatchStart() + batchOffset];
    }

    @Override
    public boolean moveBatchToOutput(int batchOffset) {
        if (this.level == null || this.level.isClientSide()) return false;
        if (applyMoveBatchToOutput(batchOffset)) {
            this.setChanged();
            return true;
        }
        return false;
    }

    /** 纯数据移动（服务端/客户端共用）：把依赖库条目移到依赖库输出槽并前移补洞。 */
    public boolean applyMoveBatchToOutput(int batchOffset) {
        if (batchOffset < 0 || batchOffset >= batchCapacity()) return false;
        int src = getBatchStart() + batchOffset;
        ItemStack entry = items[src];
        if (entry.isEmpty()) return false;
        if (!items[getDepOutputIndex()].isEmpty()) return false; // 依赖库输出被占用则不移动
        items[getDepOutputIndex()] = entry;
        for (int i = src; i < getBatchStart() + batchCapacity() - 1; i++) {
            items[i] = items[i + 1];
        }
        items[getBatchStart() + batchCapacity() - 1] = ItemStack.EMPTY;
        return true;
    }

    @Override
    public boolean removeBatchItem(int batchOffset) {
        if (this.level == null || this.level.isClientSide()) return false;
        if (batchOffset < 0 || batchOffset >= batchCapacity()) return false;
        ItemStack removed = items[getBatchStart() + batchOffset];
        if (removed.isEmpty()) return false;
        if (applyRemoveBatchItem(batchOffset)) {
            // "删除"是丢弃不是销毁：直接清槽会让依赖库文件凭空消失（点错即丢代码）。
            Block.popResource(this.level, this.getBlockPos(), removed);
            this.setChanged();
            return true;
        }
        return false;
    }

    /** 纯数据删除（服务端/客户端共用）：删除依赖库条目并前移补洞。 */
    public boolean applyRemoveBatchItem(int batchOffset) {
        if (batchOffset < 0 || batchOffset >= batchCapacity()) return false;
        int src = getBatchStart() + batchOffset;
        if (items[src].isEmpty()) return false;
        items[src] = ItemStack.EMPTY;
        for (int i = src; i < getBatchStart() + batchCapacity() - 1; i++) {
            items[i] = items[i + 1];
        }
        items[getBatchStart() + batchCapacity() - 1] = ItemStack.EMPTY;
        return true;
    }

    protected int batchOccupiedCount() {
        int c = 0;
        for (int i = getBatchStart(); i < getBatchStart() + batchCapacity(); i++) {
            if (!items[i].isEmpty()) c++;
        }
        return c;
    }

    // ===================== Container =====================

    @Override
    public int getContainerSize() { return items.length; }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int index) { return items[index]; }

    @Override
    public ItemStack removeItem(int index, int count) {
        ItemStack split = items[index].split(count);
        if (!split.isEmpty()) this.setChanged();
        return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack s = items[index];
        items[index] = ItemStack.EMPTY;
        return s;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        items[index] = stack;
        if (stack.getCount() > stack.getMaxStackSize()) {
            stack.setCount(stack.getMaxStackSize());
        }
        this.setChanged();
    }

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {
        for (int i = 0; i < items.length; i++) items[i] = ItemStack.EMPTY;
    }

    // ===================== MenuProvider（显示名/菜单由子类实现）=====================

    @Override
    public void writeClientSideData(ServerPlayer player, AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(this.getBlockPos());
    }

    @Override
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(this.getBlockPos());
    }

    // ===================== 持久化 (ValueIO) =====================
    // 用 OPTIONAL_CODEC：允许空槽（air/数量0）正常序列化，避免 MC26 的 ItemStack.CODEC 报 "must not be air"
    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        var inv = output.list("inventory", ItemStack.OPTIONAL_CODEC);
        for (ItemStack s : items) inv.add(s);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        int i = 0;
        for (ItemStack s : input.listOrEmpty("inventory", ItemStack.OPTIONAL_CODEC)) {
            if (i < items.length) items[i++] = s;
        }
    }
}
