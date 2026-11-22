package dev.programmablefurnace.compiler;

import dev.programmablefurnace.base.ModLog;
import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.CompilerAnalyzer;
import dev.programmablefurnace.base.ContentState;
import dev.programmablefurnace.base.MachineConfigs;
import dev.programmablefurnace.item.CodeFileItem;
import dev.programmablefurnace.machine.MachineBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 编译器方块实体：继承通用 {@link MachineBlockEntity}。
 * 槽位布局 = {@code [0]编译输入 + [1]编译输出 + [2]依赖库输入 + [3]依赖库输出 + [4..4+N-1]依赖库容器}。
 * 依赖库输入规则：push-front（最新在最前），不限制可执行文件数量（编译器不执行执行函数）。
 * 切换界面时覆写 {@link #applySwitchMode} 把两对 in/out 槽物品按方向迁移（in→in、out→out）。
 */
public class CompilerBlockEntity extends MachineBlockEntity {

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;
    public static final int SLOT_BATCH_INPUT = 2;
    public static final int SLOT_BATCH_OUTPUT = 3;
    public static final int BATCH_START = 4;
    public static final int BASE_SLOT_COUNT = 4;

    public CompilerBlockEntity(BlockPos pos, BlockState state) {
        super(CompilerRegistration.BLOCK_ENTITY.get(), pos, state,
                BASE_SLOT_COUNT, MachineConfigs.compiler().capacity());
    }

    /** 依赖库区容量（编译器独立配置 compiler_config.json 的 capacity，默认 27）。保留静态供外部引用。 */
    public static int configBatchCapacity() {
        return MachineConfigs.compiler().capacity();
    }

    // ===================== 子类钩子 =====================

    @Override
    protected int getWorkInputIndex() { return SLOT_INPUT; }

    @Override
    protected int getDepInputIndex() { return SLOT_BATCH_INPUT; }

    @Override
    protected int getDepOutputIndex() { return SLOT_BATCH_OUTPUT; }

    @Override
    protected int getBatchStart() { return BATCH_START; }

    @Override
    protected MachineConfigs.Machine getMachineConfig() { return MachineConfigs.compiler(); }

    /**
     * 容器层的"这个槽收什么"（漏斗/管道走这里，界面的 {@code Slot.mayPlace} 走同一判断）。
     *
     * <p>编译输入与依赖库输入只收代码文件；产物槽与依赖库容器区一律拒收——它们只由编译流程写入。</p>
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return switch (slot) {
            case SLOT_INPUT, SLOT_BATCH_INPUT -> stack.getItem() instanceof CodeFileItem;
            default -> false;
        };
    }

    // ===================== UI 模式切换（覆写：迁移两对 in/out 槽物品）=====================

    /**
     * 切换界面并把两对 in/out 槽物品按方向迁移：切到依赖库(1)时 编译in→依赖库in、编译out→依赖库out；
     * 切回编译(0)时反向。目标槽为空才迁移（不覆盖已有物品），避免丢物品。
     */
    @Override
    public boolean applySwitchMode(int newMode) {
        if (newMode == this.uiMode) return false;
        if (newMode == 1) { // 编译 → 依赖库：in→in, out→out
            moveSlotIfFree(SLOT_INPUT, SLOT_BATCH_INPUT);
            moveSlotIfFree(SLOT_OUTPUT, SLOT_BATCH_OUTPUT);
        } else {            // 依赖库 → 编译：in→in, out→out
            moveSlotIfFree(SLOT_BATCH_INPUT, SLOT_INPUT);
            moveSlotIfFree(SLOT_BATCH_OUTPUT, SLOT_OUTPUT);
        }
        this.uiMode = newMode;
        return true;
    }

    private void moveSlotIfFree(int from, int to) {
        if (items[to].isEmpty() && !items[from].isEmpty()) {
            items[to] = items[from];
            items[from] = ItemStack.EMPTY;
        }
    }

    // ===================== BatchStorage（依赖库输入规则）=====================

    /**
     * 依赖库"输入"：固定从依赖库输入槽(SLOT_BATCH_INPUT=2)取物品 push 进批量容器。
     * 编译器 push-front：最新放最前；不限制可执行文件数量（可放多个）。
     * <p>依赖库只收可链接的库文件：空白文件(BLANK)与编译失败文件(FAILED)均禁止导入。
     * 编译流程按 CMake 模型（编译→链接→打包），依赖库内的文件作为可链接的库参与后续流程，
     * 空白源文件与失败文件会污染链接/打包。</p>
     */
    @Override
    public boolean moveInputToBatch(int inputSlotIndex) {
        if (this.level == null || this.level.isClientSide()) return false;
        ItemStack input = items[SLOT_BATCH_INPUT];
        if (input.isEmpty()) return false;
        if (batchOccupiedCount() >= batchCapacity()) return false;
        // 依赖库只接受可执行文件与依赖文件；空白/编译失败文件不能导入（影响后续链接/打包流程）
        ContentState state = CodeStorage.getContentState(input);
        if (state == ContentState.BLANK || state == ContentState.FAILED) return false;
        for (int i = BATCH_START + batchCapacity() - 1; i > BATCH_START; i--) {
            items[i] = items[i - 1];
        }
        ItemStack one = input.copy();
        one.setCount(1);
        items[BATCH_START] = one;
        input.shrink(1);
        if (input.isEmpty()) {
            items[SLOT_BATCH_INPUT] = ItemStack.EMPTY;
        }
        this.setChanged();
        return true;
    }

    // ===================== 编译逻辑 =====================

    /**
     * 编译：只处理输入槽中的 1 个编码文件。依据代码内容决定结果状态：
     * 可执行 / 空白 / 失败 → 消耗输入 1 个，输出对应类型的文件到输出槽。
     * <p>失败时把该阶段的全部错误码与位置写到产物物品上（{@link CodeStorage#setCompileErrors}），
     * 玩家在输出槽的 tooltip 里就能看到"第几行、什么错"，不必再翻日志。</p>
     *
     * @return 本次编译产生的错误文本；成功或未编译时为空列表（调用方据此决定要不要提示玩家）
     */
    public List<String> tryCompile(String code) {
        if (this.level == null || this.level.isClientSide()) return List.of();
        ItemStack input = items[SLOT_INPUT];
        if (input.isEmpty()) return List.of();
        if (!(input.getItem() instanceof CodeFileItem)) return List.of(); // 只接受编码文件
        if (CodeStorage.getContentState(input) == ContentState.READONLY) return List.of(); // 只读文件不可重新编译
        if (!items[SLOT_OUTPUT].isEmpty()) return List.of(); // 输出被占用则不编译

        CompilerAnalyzer.CompileResult result = CompilerAnalyzer.compile(code);
        List<String> errors = result.errors().stream().map(Object::toString).toList();

        ItemStack out = new ItemStack(CodeFileItem.codeFileFor(result.state()));
        CodeStorage.setCode(out, code);
        // 状态与文件类型必须随产物一起写：只有 tag 正确，依赖库才能认出"这是依赖文件"，
        // 进而按 output_allowed 拒绝把它当成品输出（此前只 setCode，依赖文件被当成运行库空白文件）。
        CodeStorage.setContentState(out, result.state());
        var outType = CodeStorage.fileTypeFor(result.state());
        if (outType != null) CodeStorage.setFileType(out, outType);
        if (!errors.isEmpty()) {
            CodeStorage.setCompileErrors(out, errors);
            ModLog.LOG.info("[PF] 编译失败，{} 条错误：{}", errors.size(), errors.get(0));
        }
        items[SLOT_OUTPUT] = out;
        input.shrink(1);
        if (input.isEmpty()) {
            items[SLOT_INPUT] = ItemStack.EMPTY;
        }
        this.setChanged();
        return errors;
    }

    // ===================== MenuProvider =====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.programmablefurnace.compiler");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CompilerMenu(containerId, playerInventory, this);
    }
}

