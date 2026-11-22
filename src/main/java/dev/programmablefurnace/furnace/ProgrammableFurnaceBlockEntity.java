package dev.programmablefurnace.furnace;

import dev.programmablefurnace.base.ModLog;
import dev.programmablefurnace.FuelConfig;
import dev.programmablefurnace.base.BatchInputRules;
import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.CompilerAnalyzer;
import dev.programmablefurnace.base.ContentState;
import dev.programmablefurnace.base.IExecutor;
import dev.programmablefurnace.base.MachineConfigs;
import dev.programmablefurnace.compiler.exec.ExecResult;
import dev.programmablefurnace.compiler.exec.Executor;
import dev.programmablefurnace.item.CodeFileItem;
import dev.programmablefurnace.item.UsbCardItem;
import dev.programmablefurnace.item.BoostCardItem;
import dev.programmablefurnace.machine.MachineBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可编程熔炉（执行器）方块实体：继承通用 {@link MachineBlockEntity}，另实现 {@link ContainerData}（进度同步）。
 * 槽位布局（索引唯一定义在 {@link FurnaceLayout}，这里的常量只是转发）：
 * {@code [0..3]输入 [4]燃料 [5..8]输出 | [9]依赖库输入 [10]依赖库输出 | [11..14]拓展槽 | [15..15+N-1]依赖库容器}。
 * 程序只从依赖库页的输入槽导入、落在依赖库容器里执行（工作页没有放程序的格子）。
 * 依赖库输入规则（覆写）：只能可执行/依赖文件；可执行文件唯一且排最上；依赖库文件在下方。
 * 两界面槽位独立共存，切换只改生效套（不迁移，用基类 {@link #applySwitchMode} 默认实现）。
 * tick：每 20 tick 读一次当前程序（依赖库容器里第一个可运行文件）的代码，代码变化才重建运行器，随后每 tick 驱动 RunLoop。
 */
public class ProgrammableFurnaceBlockEntity extends MachineBlockEntity implements ContainerData {

    private static final Logger LOGGER = LoggerFactory.getLogger("ProgrammableFurnace");

    // ===== 槽位常量：唯一真源是 FurnaceLayout，这里只做转发，避免多份手写索引走散 =====
    public static final int SLOT_INPUT = FurnaceLayout.SLOT_INPUT;
    public static final int INPUT_COUNT = FurnaceLayout.INPUT_COUNT;
    public static final int SLOT_FUEL = FurnaceLayout.SLOT_FUEL;
    public static final int SLOT_OUTPUT = FurnaceLayout.SLOT_OUTPUT;
    public static final int OUTPUT_COUNT = FurnaceLayout.OUTPUT_COUNT;
    public static final int SLOT_DEP_INPUT = FurnaceLayout.SLOT_DEP_INPUT;
    public static final int SLOT_DEP_OUTPUT = FurnaceLayout.SLOT_DEP_OUTPUT;
    /** 拓展槽：放 U盘扩展卡等拓展物品，决定工作界面多输入/输出布局（任一槽有卡 → 其 guiMode；全空 → 111）。 */
    public static final int EXTRA_START = FurnaceLayout.EXTRA_START;
    public static final int EXTRA_COUNT = FurnaceLayout.EXTRA_COUNT;
    public static final int DEP_START = FurnaceLayout.DEP_START;
    public static final int BASE_SLOT_COUNT = FurnaceLayout.BASE_SLOT_COUNT;
    // ===== 燃料属性（点火时从 Config 查表设置，脚本不可见）=====
    /** 本次燃烧燃料的进度上限；<=0 时回退全局默认。 */
    private int burnMaxProgress = 0;
    /** 本次燃烧燃料的每 tick 进度增量；<=0 时回退全局默认。 */
    private int burnProgressPerTick = 0;
    /** 防重：每槽上次 advance 的游戏 tick（防止脚本同一 tick 连写多个 advance 加速）。 */
    private final long[] lastAdvanceTick = new long[INPUT_COUNT];

    public int burnMaxProgress() { return burnMaxProgress > 0 ? burnMaxProgress : FuelConfig.maxProgressDefault(); }
    public int burnProgressPerTick() { return burnProgressPerTick > 0 ? burnProgressPerTick : FuelConfig.progressPerTickDefault(); }
    public void setBurnConfig(int maxP, int perTick) { this.burnMaxProgress = maxP; this.burnProgressPerTick = perTick; }

    /** 推进指定槽进度（每 tick 每槽最多推进一次，防脚本重复调用加速）；返回是否达到上限。 */
    public boolean advanceProgress(int slot, long gameTime) {
        if (lastAdvanceTick[slot] == gameTime) {
            return progress[slot] >= burnMaxProgress();   // 本 tick 已推进过：不额外推进
        }
        lastAdvanceTick[slot] = gameTime;
        int p = progress[slot] + burnProgressPerTick();
        progress[slot] = Math.max(0, Math.min(p, burnMaxProgress()));
        setChanged();
        return p >= burnMaxProgress();
    }

    /** 每输入槽独立烧炼进度（脚本驱动，供 GUI 每条箭头）。 */
    private final int[] progress = new int[INPUT_COUNT];
    private int burnTime = 0;
    /** 本次燃烧的总燃时（ignite 时记录），供 GUI 火焰按比例归一化（burnTime / litDuration）。 */
    private int litDuration = 0;
    /** 是否运行中：GUI"开始/终止"按钮控制；仅 running=true 时 tick 才跑 RunLoop。 */
    private boolean running = false;
    /** 工作界面 GUI 模式（槽位布局编码：111/212/313/414），默认 111；脚本 setGui 切换，同步到客户端选背景图。 */
    private int guiMode = 111;

    // 程序执行缓存（跨 tick 持久，草稿 §3.8）：复用同一运行器以保持模块级变量；依赖库内容变化才重建
    private String cachedSignature;
    private Executor cachedExecutor;
    /** 上一次 RunLoop 的错误文本；仅当它变化时才写日志，避免每 tick 刷同一条错误。 */
    private String lastRunLoopError;
    /** 上一 tick 的红石供电状态：用于把"持续供电"折成一次上升沿事件（不持久化，重载即视为无信号）。 */
    private boolean redstonePowered;
    /**
     * 最近一次依赖库编译失败的错误（已格式化为"文件:行:列 CODE 消息"）。
     * <p>为什么要存一份：编译发生在 {@link #tick} 的每 20 tick 检测里，失败时玩家可能已经关掉界面，
     * 只写日志等于没有提示。这份数据有两个出口——写到依赖库里的源文件 tooltip，以及玩家按"开始"时
     * 由 {@link FurnaceInteraction#setRunning} 直接回话。</p>
     */
    private List<String> lastCompileErrors = List.of();

    public ProgrammableFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(FurnaceRegistration.BLOCK_ENTITY.get(), pos, state,
                BASE_SLOT_COUNT, MachineConfigs.furnace().capacity());
    }

    // ===================== 子类钩子 =====================

    @Override
    protected int getWorkInputIndex() { return SLOT_INPUT; }

    @Override
    protected int getDepInputIndex() { return SLOT_DEP_INPUT; }

    @Override
    protected int getDepOutputIndex() { return SLOT_DEP_OUTPUT; }

    @Override
    protected int getBatchStart() { return DEP_START; }

    @Override
    protected MachineConfigs.Machine getMachineConfig() { return MachineConfigs.furnace(); }

    /**
     * 容器层的"这个槽收什么"。GUI 的 {@code Slot.mayPlace} 只是显示层约束，
     * 漏斗/其它模组的管道走的是这里；两处必须同源，否则会出现"界面放不进去、漏斗能塞进去"。
     *
     * <p>产物槽（输出/依赖库输出）与依赖库容器区一律拒收：它们只由程序逻辑写入。
     * 拓展槽只收 {@code furnace_expansion} tag 的物品；依赖库输入槽只收代码文件。</p>
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return switch (FurnaceLayout.slotKind(slot, batchCapacity())) {
            case DEP_INPUT -> stack.getItem() instanceof CodeFileItem;
            case EXTRA -> stack.is(ProgrammableFurnaceMenu.FURNACE_EXPANSION_TAG);
            case OUTPUT, DEP_OUTPUT, BATCH -> false;
            case INPUT, FUEL, OUT_OF_RANGE -> true;
        };
    }

    /** 依赖库容器容量（执行器独立配置 furnace_config.json 的 capacity）。保留静态供外部引用。 */
    public static int depCapacity() {
        return MachineConfigs.furnace().capacity();
    }

    /** 第一个输入槽进度（兼容旧脚本 furnace.progress 单值读写）。 */
    public int getProgress() { return progress[0]; }
    public int getBurnTime() { return burnTime; }
    public int getLitDuration() { return litDuration; }
    /** 脚本经 Env 的 {@code 熔炉.点火()} 设置燃烧剩余 tick（燃料物理计时），并记录本次燃时总量。 */
    public void setBurnTime(int t) {
        burnTime = t;
        if (t > 0) litDuration = t;
    }

    /** 指定输入槽的烧炼进度（GUI 每条箭头按各自槽位进度动画）。 */
    public int getProgress(int slot) { return progress[slot]; }

    /** 脚本驱动推进第一个输入槽进度（兼容单值写法）。 */
    public void setProgress(int p) { setProgress(0, p); }

    /** 脚本驱动推进指定输入槽进度（RunLoop 每 tick 写 furnace.setProgress(i, p)），供 GUI 对应箭头动画。 */
    public void setProgress(int slot, int p) {
        progress[slot] = Math.max(0, Math.min(p, burnMaxProgress()));
    }

    public boolean isRunning() { return running; }

    /** 设置运行状态（GUI 开始/终止按钮触发）；变化才标记脏以触发方块实体数据同步到客户端。 */
    public void setRunning(boolean r) {
        if (this.running != r) {
            this.running = r;
            this.setChanged();
        }
    }

    /** 当前工作界面 GUI 模式。 */
    public int getGuiMode() { return guiMode; }

    /** 切换工作界面 GUI 模式（111/212/313/414）；变化才标记脏以同步客户端。 */
    public void setGuiMode(int mode) {
        if (this.guiMode != mode) {
            this.guiMode = mode;
            this.setChanged();
        }
    }

    /** 拓展槽物品变化 → 按槽内 U盘扩展卡重推 guiMode（决定 GUI 布局）。覆写 {@code Container.setItem}。 */
    @Override
    public void setItem(int index, ItemStack stack) {
        super.setItem(index, stack);
        if (index >= EXTRA_START && index < EXTRA_START + EXTRA_COUNT) {
            setGuiMode(guiModeFromExtras());
        }
    }

    /**
     * 从拓展槽推导 GUI 布局编码：扫描全部拓展槽，取同 tag（{@link UsbCardItem#USB_EXPANSION_TAG}）
     * 中等级最高的 U盘扩展卡生效（212/313/414）；空/无同 tag 卡 → 111 基础布局。
     */
    public int guiModeFromExtras() {
        int bestTier = 0;
        int bestMode = 111;
        for (int i = 0; i < EXTRA_COUNT; i++) {
            ItemStack s = items[EXTRA_START + i];
            if (s.isEmpty() || !s.is(UsbCardItem.USB_EXPANSION_TAG)) continue;
            if (s.getItem() instanceof UsbCardItem card && card.tier() > bestTier) {
                bestTier = card.tier();
                bestMode = card.guiMode();
            }
        }
        return bestMode;
    }

    /** 扫描功能槽取燃烧优化卡（usb_boost tag）最高档；无 → 0（不优化）。 */
    public int boostTier() {
        int best = 0;
        for (int i = 0; i < EXTRA_COUNT; i++) {
            ItemStack s = items[EXTRA_START + i];
            if (s.isEmpty() || !s.is(BoostCardItem.USB_BOOST_TAG)) continue;
            if (s.getItem() instanceof BoostCardItem card && card.tier() > best) best = card.tier();
        }
        return best;
    }

    // ===================== ContainerData (GUI 同步，经 Menu.addDataSlots) =====================
    // 索引含义见 FurnaceDataIndex（进度 0..3 / burnTime / running / litDuration / guiMode / burnMaxProgress）
    @Override
    public int get(int index) {
        if (FurnaceDataIndex.isProgress(index)) return progress[index - FurnaceDataIndex.PROGRESS_FIRST];
        return switch (index) {
            case FurnaceDataIndex.BURN_TIME -> burnTime;
            case FurnaceDataIndex.RUNNING -> running ? 1 : 0;
            case FurnaceDataIndex.LIT_DURATION -> litDuration;
            case FurnaceDataIndex.GUI_MODE -> guiMode;
            case FurnaceDataIndex.BURN_MAX_PROGRESS -> burnMaxProgress();
            default -> 0;
        };
    }

    @Override
    public void set(int index, int value) {
        if (FurnaceDataIndex.isProgress(index)) {
            progress[index - FurnaceDataIndex.PROGRESS_FIRST] = value;
            return;
        }
        switch (index) {
            case FurnaceDataIndex.BURN_TIME -> burnTime = value;
            case FurnaceDataIndex.RUNNING -> running = value != 0;
            case FurnaceDataIndex.LIT_DURATION -> litDuration = value;
            case FurnaceDataIndex.GUI_MODE -> guiMode = value;
            case FurnaceDataIndex.BURN_MAX_PROGRESS -> burnMaxProgress = value;
        }
    }

    @Override
    public int getCount() { return FurnaceDataIndex.COUNT; }


    // ===================== BatchStorage（依赖库输入规则）=====================

    /**
     * 依赖库"输入"：固定从依赖库输入槽(SLOT_DEP_INPUT=4)取 1 个 push 进容器。
     * 规则（{@link BatchInputRules}）：① 只能输入可执行文件(EXECUTABLE)与依赖库文件(DEPENDENCY)；
     *       ② 可执行文件唯一——若容器已有可执行文件，再输可执行拒绝；
     *       ③ 排序——可执行文件排最上(索引0)，依赖库文件追加到末尾。
     */
    @Override
    public boolean moveInputToBatch(int inputSlotIndex) {
        if (this.level == null || this.level.isClientSide()) return false;
        ItemStack input = items[SLOT_DEP_INPUT];
        if (input.isEmpty()) return false;
        ContentState st = CodeStorage.getContentState(input);
        int insertAt = BatchInputRules.dependencyInsertIndex(st, batchOccupiedCount(), batchCapacity(), depBatchHasExecutable());
        if (insertAt < 0) return false;
        for (int i = DEP_START + batchCapacity() - 1; i > DEP_START + insertAt; i--) {
            items[i] = items[i - 1];
        }
        ItemStack one = input.copy();
        one.setCount(1);
        items[DEP_START + insertAt] = one;
        input.shrink(1);
        if (input.isEmpty()) {
            items[SLOT_DEP_INPUT] = ItemStack.EMPTY;
        }
        this.setChanged();
        return true;
    }

    /** 依赖库容器里是否已有可执行程序（决定新导入的可执行文件是否要被拒；只看容器，不看程序槽）。 */
    private boolean depBatchHasExecutable() {
        for (int i = DEP_START; i < DEP_START + batchCapacity(); i++) {
            if (CodeFileItem.holdsRunnableCode(items[i])) {
                return true;
            }
        }
        return false;
    }

    /** 依赖库里是否有可运行的代码文件；GUI 据此显示"开始/终止"按钮。 */
    public boolean hasExecutableProgram() {
        return !currentModuleSources().isEmpty();
    }

    // ===================== 程序执行（跨 tick 持久缓存） =====================
    // 复用同一 Executor 实例调用多次 tick，其模块级 globals 变量跨 tick 保持（草稿 §3.8）。
    // 仅当槽内代码字符串变化时才重建编译结果与运行器（此时 globals 重置，程序重新开始）。

    /**
     * 依赖库里这批代码变化时重建运行器（跨 tick 持久缓存）。本方法只编译+重建，不执行；
     * RunLoop 每 game tick 由 {@link #tick} 调用 {@link Executor#tick()} 驱动。
     *
     * <p>草稿 §2.4 / §4.9：一个依赖库里可以有多个文件，按各自的 {@code MODULE("…")} 名互相 import。
     * 所以这里不是"编译一个字符串"，而是把整个依赖库当模块集合交给 {@link CompilerAnalyzer#compileModules}，
     * 由它挑出含 {@code RunLoop} 的入口模块、校验跨模块 import（M03/M04/M05）、把依赖声明合并进来。</p>
     *
     * @return true 表示当前这批代码里有可执行入口、运行器可用
     */
    public boolean runProgram(List<CompilerAnalyzer.ModuleSource> sources) {
        if (level == null || level.isClientSide() || sources == null || sources.isEmpty()) return false;
        String signature = signatureOf(sources);
        if (cachedExecutor == null || !signature.equals(cachedSignature)) {
            try {
                CompilerAnalyzer.ModulesResult r = CompilerAnalyzer.compileModules(sources);
                if (r.state() != ContentState.EXECUTABLE || r.table() == null) {
                    // 编译失败：错误要落在玩家看得见的地方（源文件 tooltip + 按"开始"时回话），日志只做留痕
                    lastCompileErrors = r.errors().stream().map(Object::toString).toList();
                    markModuleErrors(r);
                    if (r.hasErrors()) {
                        LOGGER.warn("[PF] 程序编译失败，已跳过：{}", r.errors().get(0));
                    }
                    cachedSignature = null;
                    cachedExecutor = null;
                    return false;
                }
                // Executor.of 会执行模块级变量初始化（evalInit），坏代码会在这一步抛运行期异常；
                // 本方法由 ticker 每 20 tick 调一次，异常冒出去就是崩服，所以整段收口。
                cachedExecutor = Executor.of(r.decls(), r.table(), new FurnaceEnv(level, worldPosition, this));
                cachedSignature = signature;
                lastCompileErrors = List.of();
                clearModuleErrors();
            } catch (RuntimeException | StackOverflowError ex) {
                LOGGER.warn("[PF] 程序初始化失败，已跳过本次：{}", ex.toString());
                cachedSignature = null;
                cachedExecutor = null;
                return false;
            }
        }
        return true;
    }

    /** 依赖库这批代码的"指纹"：名字 + 内容，用于判断是否需要重编译。 */
    private static String signatureOf(List<CompilerAnalyzer.ModuleSource> sources) {
        StringBuilder sb = new StringBuilder();
        for (CompilerAnalyzer.ModuleSource s : sources) {
            sb.append(s.name()).append('\u0000').append(s.code()).append('\u0000');
        }
        return sb.toString();
    }

    /** 依赖库里所有可运行/依赖库文件（各自带名字），交给编译器当模块集合。 */
    private List<CompilerAnalyzer.ModuleSource> currentModuleSources() {
        List<CompilerAnalyzer.ModuleSource> list = new ArrayList<>();
        for (int i = DEP_START; i < DEP_START + depCapacity(); i++) {
            ItemStack s = items[i];
            if (!CodeFileItem.holdsRunnableCode(s)) continue;
            list.add(new CompilerAnalyzer.ModuleSource(sourceNameAt(s, i), CodeStorage.getCode(s)));
        }
        return list;
    }

    /**
     * 源文件在编译器眼里的模块名：优先 {@code MODULE("…")} 写的名字，没写就用"代码N"。
     * <p>编译错误回填与模块枚举必须共用这一处，否则回填会写到错的物品上。</p>
     */
    private static String sourceNameAt(ItemStack stack, int slot) {
        String name = CodeStorage.getSourceName(stack);
        if (name == null || name.isBlank()) return "代码" + (slot - DEP_START + 1);
        return name;
    }

    /**
     * 把每个模块的错误写回它自己的源文件（成功模块清掉旧错误）。
     * <p>这样玩家在依赖库页悬停文件就能看到"第几行、什么错"，不必猜为什么熔炉不干活。
     * 注意不改物品的 {@code content_state}：一旦标成 FAILED，它就会被 {@link CodeFileItem#holdsRunnableCode} 排除，
     * 于是"坏文件"从依赖库里消失、下次编译悄悄成功——那才是最坏的结果。</p>
     */
    private void markModuleErrors(CompilerAnalyzer.ModulesResult result) {
        for (CompilerAnalyzer.ModuleResult module : result.modules()) {
            ItemStack stack = findSourceByName(module.name());
            if (stack == null) continue;
            CodeStorage.setCompileErrors(stack, module.errors().stream().map(Object::toString).toList());
        }
    }

    /** 编译成功后清掉依赖库里残留的旧错误，避免"已经好了但 tooltip 还是红的"。 */
    private void clearModuleErrors() {
        for (int i = DEP_START; i < DEP_START + depCapacity(); i++) {
            ItemStack s = items[i];
            if (CodeFileItem.holdsRunnableCode(s) && !CodeStorage.getCompileErrors(s).isEmpty()) {
                CodeStorage.setCompileErrors(s, List.of());
            }
        }
    }

    /** 按模块名找回依赖库里的那个 ItemStack（名字规则与 {@link #sourceNameAt} 一致）。 */
    private ItemStack findSourceByName(String moduleName) {
        for (int i = DEP_START; i < DEP_START + depCapacity(); i++) {
            ItemStack s = items[i];
            if (!CodeFileItem.holdsRunnableCode(s)) continue;
            if (sourceNameAt(s, i).equals(moduleName)) return s;
        }
        return null;
    }

    /** 最近一次编译失败的错误文本；空列表表示当前这批代码是好的（或还没编译过）。 */
    public List<String> lastCompileErrors() {
        return lastCompileErrors;
    }

    /**
     * 当前要运行的程序代码：在依赖库容器里找可执行文件；没有则 null。
     * <p>程序文件只有一个入口——依赖库页的输入槽（{@link #SLOT_DEP_INPUT}）导入到依赖库容器，批量规则保证
     * "可执行文件唯一且排最上"，所以这里扫到第一个可运行文件就是要跑的那个。工作页不放程序（2026-11-17 移除
     * 了那个界面上没有入口、只能靠漏斗塞的"程序槽"）。</p>
     */
    private String currentExecutableCode() {
        for (int i = DEP_START; i < DEP_START + depCapacity(); i++) {
            if (CodeFileItem.holdsRunnableCode(items[i])) {
                return CodeStorage.getCode(items[i]);
            }
        }
        return null;
    }

    /** 每 game tick 跑一次 RunLoop（脚本驱动决策/搬运），返回本次执行状态；无程序时跳过。 */
    public ExecResult runLoopTick() {
        if (cachedExecutor == null) return ExecResult.noEntry("");
        ExecResult res = cachedExecutor.tick();
        // 只在"错误内容变化"时打日志：脚本每 tick 都可能报同一个错，逐 tick 打会淹掉整个日志。
        String message = res.ok() ? null : res.message();
        if (!Objects.equals(message, lastRunLoopError)) {
            if (message == null) {
                LOGGER.info("[PF] runloop 恢复正常");
            } else {
                LOGGER.error("[PF] runloop error: {}", message);
            }
            lastRunLoopError = message;
        }
        return res;
    }

    /** 执行一次事件入口（`RunLoop()`，无参）：由红石上升沿触发，与常驻轮询无关。 */
    public ExecResult runSignalTick() {
        if (cachedExecutor == null) return ExecResult.noEntry("");
        ExecResult res = cachedExecutor.trigger();
        // 没有无参入口是正常情况（多数脚本只写常驻版），不当作错误、也不打日志
        if (res.status() == ExecResult.Status.NO_ENTRY) return res;
        String message = res.ok() ? null : res.message();
        if (!Objects.equals(message, lastRunLoopError)) {
            if (message == null) {
                LOGGER.info("[PF] runloop 恢复正常");
            } else {
                LOGGER.error("[PF] signal runloop error: {}", message);
            }
            lastRunLoopError = message;
        }
        return res;
    }

    // ===================== 每 tick 逻辑（方向 B：RunLoop 每 tick + 燃料物理计时） =====================
    // 烧炼决策与产物搬运由程序槽脚本经 Env 驱动（可编程配方）；tick 每 game tick 跑一次 RunLoop（跨 tick 持久），
    // 每 20 tick 才读程序槽检测代码变化（避免每 tick 重编译），燃料仅随时间递减。

    public static void tick(Level level, BlockPos pos, BlockState state, ProgrammableFurnaceBlockEntity be) {
        if (!level.isClientSide()) {
            // 每 20 tick 读一次当前程序（程序槽优先，其次依赖库容器）的代码：代码变化则重建运行器；无则停止
            if (level.getGameTime() % 20 == 0) {
                List<CompilerAnalyzer.ModuleSource> modules = be.currentModuleSources();
                if (modules.isEmpty()) {
                    be.cachedSignature = null;
                    be.cachedExecutor = null;   // 依赖库里没有可运行文件 → 停止执行
                } else if (state.getBlock() instanceof IExecutor executor) {
                    executor.runCode(level, pos, state, modules);   // → be.runProgram → 重建
                }
            }
            // 红石上升沿触发事件入口 RunLoop()（无参）：只在"无信号 → 有信号"的那一 tick 跑一次。
            // 用轮询而不是 neighborChanged：方块本来每 tick 都会走到这里，多一次 hasNeighborSignal 很便宜，
            // 而 neighborChanged 会随相邻方块更新次数变化（同一 tick 可能多次），上升沿判断反而更难写对。
            boolean powered = level.hasNeighborSignal(pos);
            if (powered && !be.redstonePowered) {
                be.runSignalTick();
            }
            be.redstonePowered = powered;
            // 每 game tick 跑一次 RunLoop（仅"运行中"：脚本驱动补料/点火/推进/搬运）
            if (be.running) {
                be.runLoopTick();
                // 运行期状态日志（每 100 tick，debug 级）：诊断烧炼/产出/搬运
                if (level.getGameTime() % 100 == 0) {
                    ItemStack in = be.items[SLOT_INPUT], fu = be.items[SLOT_FUEL], ou = be.items[SLOT_OUTPUT];
                    LOGGER.debug("[PF] t={} prog={} burn={} running={} in={}x{} fuel={}x{} out={}x{}",
                            level.getGameTime(), be.progress[0], be.burnTime, be.running,
                            in.getItem().getDescriptionId(), in.getCount(),
                            fu.getItem().getDescriptionId(), fu.getCount(),
                            ou.getItem().getDescriptionId(), ou.getCount());
                }
            }
        }

        // 燃料燃烧计时：点火由脚本经 Env 的 furnace.ignite() 触发；此处仅随时间递减
        if (be.burnTime > 0) {
            be.burnTime--;
            be.setChanged();
        }
        // 同步方块燃烧状态（LIT）到世界：点燃时方块前脸变亮发光，燃料耗尽恢复不燃
        boolean lit = be.burnTime > 0;
        if (state.getValue(ProgrammableFurnaceBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(ProgrammableFurnaceBlock.LIT, lit), 3);
        }
    }

    // ===================== MenuProvider =====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.programmable_furnace");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ProgrammableFurnaceMenu(containerId, playerInventory, this);
    }

    // ===================== 持久化：额外字段（调基类存 items）=====================

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // 每输入槽进度持久化为 IntTag 列表（重启后脚本驱动会重新推进；仅保留首槽，多槽不强制持久化）
        output.putInt("progress", progress[0]);
        output.putInt("burn_time", burnTime);
        output.putInt("lit_duration", litDuration);
        output.putBoolean("running", running);
        output.putInt("gui_mode", guiMode);
        output.putInt("burn_max_progress", burnMaxProgress);
        output.putInt("burn_progress_per_tick", burnProgressPerTick);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        progress[0] = input.getIntOr("progress", 0);
        burnTime = input.getIntOr("burn_time", 0);
        litDuration = input.getIntOr("lit_duration", 0);
        running = input.getBooleanOr("running", false);
        guiMode = input.getIntOr("gui_mode", 111);
        burnMaxProgress = input.getIntOr("burn_max_progress", 0);
        burnProgressPerTick = input.getIntOr("burn_progress_per_tick", 0);
    }

    /** 网络同步到客户端（GUI 据此显示运行状态 / 进度箭头 / 火焰 / 依赖库列表）：调基类后追加业务字段。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("progress", progress[0]);
        tag.putInt("burn_time", burnTime);
        tag.putInt("lit_duration", litDuration);
        tag.putBoolean("running", running);
        tag.putInt("gui_mode", guiMode);
        tag.putInt("burn_max_progress", burnMaxProgress);
        tag.putInt("burn_progress_per_tick", burnProgressPerTick);
        return tag;
    }

    /**
     * 运行中也要把方块实体数据（running/progress/burnTime/inventory）同步到客户端——
     * 否则"开始/终止"按钮状态与火焰/箭头动画在客户端不会实时更新
     * （默认 getUpdatePacket 返回 null，只有区块加载/开 GUI 时同步一次）。
     */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}


