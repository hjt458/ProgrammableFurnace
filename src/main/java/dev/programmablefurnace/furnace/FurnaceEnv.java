package dev.programmablefurnace.furnace;

import dev.programmablefurnace.base.ModLog;
import dev.programmablefurnace.FuelConfig;
import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.compiler.exec.Env;
import dev.programmablefurnace.compiler.exec.ExternalRef;
import dev.programmablefurnace.machine.SlotNameMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.loot.NeoForgeLootContextParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 游戏端机器原语环境（草稿 §4.10 执行器运行期 API 契约）。
 * <p>把纯 JVM 运行器 {@link Env} 桥接到真实熔炉：槽组（input/fuel/output/program/dep_input/dep_output/dep）读写、
 * {@code self}/{@code furnace} 自身引用、容器 {@code Container} 两套视图（getItems/setItems/isEmpty/slotSize/slots/setSlots）、
 * 槽位 {@code Slot}（getItem/setItem/isEmpty/getCount）、物品 {@code Item}(ItemStack) 方法、定位
 * （get/searchByTag/searchByName/getContainer/radius/region）、{@code ItemStream} 简化流转、烧炼
 * （{@code furnace.burning}/{@code furnace.burnTime}/{@code furnace.ignite()}，方向 B 脚本驱动）。
 * <p>底层全部复用 MC 原生接口（Container/ItemStack/TagKey/BlockPos 等，§4.10.8），语言只做封装；
 * 未实现的内置调用返回运行期"未实现"错误（不崩溃）。</p>
 */
public final class FurnaceEnv implements Env {

    /** 自身引用全局名（语言 API 用英文）。 */
    public static final String SELF = "self";
    public static final String FURNACE = "furnace";

    private final Level level;
    private final BlockPos pos;
    private final ProgrammableFurnaceBlockEntity machine;
    private final SlotNameMap slots;

    /** {@code get(name)} 缓存的有效期（game tick）：容器位置几乎不变，而 65³ 逐块扫描每 tick 会跑十几次。 */
    private static final long NAME_CACHE_TICKS = 20;

    /** {@code get(name)} 的缓存条目：命中的方块位置 + 记录时刻；位置每秒失效一次，避免玩家搬走箱子后一直读到空气。 */
    private record CachedContainer(BlockPos pos, long tick) {}

    /** 名称 → 上次命中的容器位置。存在的意义是让示例脚本每 tick 十几次 {@code get("chest")} 只扫描一次。 */
    private final Map<String, CachedContainer> nameCache = new HashMap<>();

    public FurnaceEnv(Level level, BlockPos pos, ProgrammableFurnaceBlockEntity machine) {
        this.level = level;
        this.pos = pos;
        this.machine = machine;
        // 槽索引只从 FurnaceLayout 推导（唯一真源）：配置里的 input_count 只决定"激活多少个输入槽"，
        // 不改变槽位置；gap = 拓展槽（U 盘卡位），脚本不可见。
        this.slots = FurnaceLayout.slotNames(machine.batchCapacity());
        if (this.slots.depStart() != FurnaceLayout.DEP_START) {
            throw new IllegalStateException("槽名映射与机器布局不一致: depStart=" + this.slots.depStart()
                    + ", DEP_START=" + FurnaceLayout.DEP_START);
        }
    }

    // ===================== Env: 外部全局 / 自身引用 =====================

    @Override
    public boolean hasExternal(String name) {
        return SELF.equals(name) || FURNACE.equals(name);
    }

    // ===================== Env: read =====================

    @Override
    public Object read(String name, Object target) {
        Object t = unwrap(target);
        if (t == null) {
            // 外部全局（仅 hasExternal 为真的名字会走到这）
            if (hasExternal(name)) return new ExternalRef(machine);
            return null;
        }
        if (t instanceof ProgrammableFurnaceBlockEntity m) {
            if ("burning".equals(name)) return m.getBurnTime() > 0;       // 是否正在烧
            if ("burnTime".equals(name)) return (long) m.getBurnTime();   // 燃烧剩余 tick
            if ("progress".equals(name)) return (long) m.getProgress();   // 烧炼进度（GUI 箭头）
            if ("inputCount".equals(name)) {                              // 当前布局激活的输入槽数（脚本多输入并行循环）
                GuiConfig.GuiMode gm = GuiConfig.mode(m.getGuiMode());
                return (long) (gm != null && gm.inputX != null ? gm.inputX.length : 1);
            }
            List<Integer> idx = slots.group(name);
            if (!idx.isEmpty()) return slotGroup(idx, machine);
            return null;   // 非槽组名 → 未知成员
        }
        if (t instanceof Container c) {
            // 容器（箱子/木桶等）成员读取：与 callContainer 对应的无参只读面
            return switch (name) {
                case "getItems" -> itemList(c);
                case "slots" -> slotGroup(allIndices(c), c);   // 箱子.slots：遍历该容器自身所有槽（不是熔炉）
                case "isEmpty" -> c.isEmpty();
                case "slotSize" -> (long) c.getContainerSize();
                default -> throw notImplemented("读取成员", t, name);
            };
        }
        if (t instanceof SlotView sv) {
            return switch (name) {
                case "item" -> new ExternalRef(sv.container.getItem(sv.index));
                case "count" -> (long) sv.container.getItem(sv.index).getCount();
                case "empty" -> sv.container.getItem(sv.index).isEmpty();
                default -> null;
            };
        }
        if (t instanceof ItemStack st) {
            return switch (name) {
                case "count" -> (long) st.getCount();
                case "empty" -> st.isEmpty();
                default -> null;
            };
        }
        throw notImplemented("读取成员", t, name);
    }

    // ===================== Env: write =====================

    @Override
    public void write(String name, Object target, Object value) {
        Object t = unwrap(target);
        // 首版最小集：仅支持脚本驱动写 furnace.progress（GUI 箭头动画）；其余成员赋值留后续。
        if (t instanceof ProgrammableFurnaceBlockEntity m && "progress".equals(name)) {
            if (value instanceof Number n) {
                m.setProgress(n.intValue());
                m.setChanged();
                return;
            }
            throw new RuntimeException("progress 需整数: " + value);
        }
        throw notImplemented("写入成员", t == null ? target : t, name);
    }

    // ===================== Env: call =====================

    @Override
    public Object call(String name, Object target, List<Object> args) {
        Object t = unwrap(target);
        if (t == null) return callGlobal(name, args);
        if (t instanceof ProgrammableFurnaceBlockEntity m) {
            if ("ignite".equals(name)) return light(m);
            if ("result".equals(name)) return resultOf(m, args);
            if ("setProgress".equals(name)) {                       // 推进指定输入槽进度（多输入并行）
                int slot = argInt(args, 0);
                if (slot >= 0 && slot < ProgrammableFurnaceBlockEntity.INPUT_COUNT)
                    m.setProgress(slot, argInt(args, 1));
                m.setChanged();
                return null;
            }
            if ("getProgress".equals(name)) {                       // 读指定输入槽进度
                int slot = Math.max(0, Math.min(argInt(args, 0), ProgrammableFurnaceBlockEntity.INPUT_COUNT - 1));
                return (long) m.getProgress(slot);
            }
            if ("advance".equals(name)) {                           // 玩家唯一燃烧接口：推进当前燃料进度（每 tick 每槽一次，防重复）
                int slot = argInt(args, 0);
                if (slot < 0 || slot >= ProgrammableFurnaceBlockEntity.INPUT_COUNT) return false;
                if (m.getBurnTime() <= 0) return false;             // 未燃烧不推进
                return m.advanceProgress(slot, level.getGameTime());
            }
            return callContainer(m, name, args);
        }
        if (t instanceof Container c) return callContainer(c, name, args);
        if (t instanceof SlotView sv) return callSlot(sv, name, args);
        if (t instanceof ItemStack st) return callItem(st, name, args);
        if (t instanceof ItemTypeValue ty) return callItemType(ty, name, args);
        if (t instanceof List<?> list) return callList(list, name, args);
        if (t instanceof ItemStreamState stream) return callStream(stream, name, args);
        throw notImplemented("调用", t, name);
    }

    /** 取脚本调用参数第 i 个并转 int。 */
    private int argInt(List<Object> args, int i) {
        return ((Number) args.get(i)).intValue();
    }

    // ===================== Env: iterable（范围 for）=====================

    @Override
    public List<?> iterable(Object obj) {
        Object t = unwrap(obj);
        if (t instanceof List<?> list) return list;   // 槽组 vector<Slot> / vector<Item> / vector<Container>
        throw notImplemented("遍历", t, "iterable");
    }

    /** 世界时间：入口 {@code RunLoop(int tick)} 的 tick 取自这里（见 {@link Env#gameTime()}）。 */
    @Override
    public long gameTime() {
        return level.getGameTime();
    }

    // ===================== 全局函数 / 定位 =====================

    private Object callGlobal(String name, List<Object> args) {
        return switch (name) {
            case "Container" -> throw notImplemented("Container 构造（自身引用模型，无需构造）", null, name);
            case "Item" -> new ExternalRef(itemValue(args));   // Item()：空物品；Item("minecraft:coal")：一个该物品
            case "ItemType" -> new ExternalRef(ItemTypeValue.of(strArg(args, 0)));   // ItemType("id")：物品类型（不带头数）
            case "ItemStream" -> new ExternalRef(args.isEmpty()
                    ? new ItemStreamState()
                    : new ItemStreamState(intArg(args, 0)));
            case "get" -> getByName(strArg(args, 0));
            case "getContainer" -> getContainer(args);
            case "searchByTag" -> searchByTag(args);
            case "searchByName" -> searchByName(args);
            case "radius" -> new ExternalRef(radiusScope(args));
            case "region" -> new ExternalRef(boxScope(args));
            default -> throw notImplemented("内置函数", null, name);
        };
    }

    /**
     * get(名称) → 按名称取最近匹配的容器实体。
     * <p>命中结果在 {@link #NAME_CACHE_TICKS} 内缓存：示例脚本的 {@code refillFuel/refillInput/returnItem}
     * 每 tick 合计调用十几次，若不缓存就是十几次 65³ 扫描。缓存只认"那个位置现在还是容器"，
     * 箱子被搬走时会自然失效并重新扫描。</p>
     * <p>扫描本身也会跳过未加载的区块——{@code level.getBlockEntity} 在未加载位置可能触发加载/生成，
     * 半径 32 已经横跨十几个区块，不能让它替玩家加载世界。</p>
     */
    private Object getByName(String target) {
        long now = level.getGameTime();
        CachedContainer cached = nameCache.get(target);
        if (cached != null && now - cached.tick() <= NAME_CACHE_TICKS
                && level.getBlockEntity(cached.pos()) instanceof Container hit) {
            return new ExternalRef(hit);
        }
        int r = 32;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = pos.offset(dx, dy, dz);
                    if (p.equals(pos)) continue;   // 跳过熔炉自身，避免把自身当容器搬物
                    if (!level.isLoaded(p)) continue;
                    if (level.getBlockEntity(p) instanceof Container c && matchesName(p, target)) {
                        nameCache.put(target, new CachedContainer(p, now));
                        ModLog.LOG.debug("[PF] get('{}') matched -> {} at {}", target,
                                level.getBlockState(p).getBlock().getDescriptionId(), p.toShortString());
                        return new ExternalRef(c);
                    }
                }
            }
        }
        nameCache.remove(target);
        ModLog.LOG.debug("[PF] get('{}') -> no match (半径 {} 内无匹配容器)", target, r);
        return List.of();   // 空结果：返回空列表（无可匹配容器）
    }

    /** 是否命中名称：方块注册 id 的 path，或方块所属标签名，与目标相同或包含目标（如 "chest" 命中 #minecraft:chests）。 */
    private boolean matchesName(BlockPos p, String target) {
        BlockState state = level.getBlockState(p);
        var key = state.getBlock().builtInRegistryHolder().key();
        if (key != null) {
            Identifier id = key.identifier();
            if (id != null && nameHits(id.getPath(), target)) return true;
        }
        // 标签名含目标：get("chest") 也能命中属于 #minecraft:chests 之类标签的方块（木桶/其它容器）
        for (var tag : state.getBlock().builtInRegistryHolder().tags().toList()) {
            Identifier ti = tag.location();
            if (ti != null && nameHits(ti.getPath(), target)) return true;
        }
        return false;
    }

    private static boolean nameHits(String path, String target) {
        return path.equalsIgnoreCase(target)
                || path.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }

    /** getContainer(坐标) → 按世界坐标定位容器。坐标可为 BlockPos 对象或 (x,y,z)。 */
    private Object getContainer(List<Object> args) {
        BlockPos p;
        Object a0 = unwrapArg(args, 0);
        if (a0 instanceof BlockPos bp) p = bp;
        else if (args.size() >= 3 && a0 instanceof Number x && args.get(1) instanceof Number y && args.get(2) instanceof Number z) {
            p = new BlockPos(x.intValue(), y.intValue(), z.intValue());
        } else {
            throw new RuntimeException("getContainer 需坐标(BlockPos)或(x,y,z)");
        }
        if (level.getBlockEntity(p) instanceof Container c) return new ExternalRef(c);
        return new ExternalRef(ItemStack.EMPTY);
    }

    /** searchByTag(基准, 标签名, 范围) → vector<Container>。 */
    private Object searchByTag(List<Object> args) {
        Scope scope = resolveScope(args, 2);
        String tag = strArg(args, 1);
        TagKey<Block> blockTag = TagKey.create(Registries.BLOCK, Identifier.parse(ns(tag)));
        List<Object> out = new ArrayList<>();
        for (BlockPos p : each(scope)) {
            if (level.getBlockEntity(p) instanceof Container c
                    && level.getBlockState(p).is(blockTag)) {
                out.add(new ExternalRef(c));
            }
        }
        return new ExternalRef(out);
    }

    /** searchByName(名称, 范围) → vector<Container>。 */
    private Object searchByName(List<Object> args) {
        Scope scope = resolveScope(args, 1);
        String name = strArg(args, 0);
        List<Object> out = new ArrayList<>();
        for (BlockPos p : each(scope)) {
            if (level.getBlockEntity(p) instanceof Container c && matchesName(p, name)) {
                out.add(new ExternalRef(c));
            }
        }
        return new ExternalRef(out);
    }

    // ===================== 机器烧炼（方向 B：脚本驱动，tick 只计时）=====================

    /**
     * furnace.ignite()：燃料槽第一格有可燃料物则消耗一个并设置燃烧剩余 tick，返回是否点火成功。
     * <p>燃料分两类：{@code fuel_config.json} 里内置的（用作者的平衡数值），以及其它原版可燃物
     * （按 MC 26.3 的 {@code CookingFuel} 组件解析燃时，见 {@link #burnDuration}）。
     * 两者都不是 → 不点火也不消耗，泥土放进去不会被当成燃料烧掉。</p>
     */
    private boolean light(ProgrammableFurnaceBlockEntity m) {
        if (m.getBurnTime() > 0) return false;   // 已在烧，不重复点火
        if (!(level instanceof ServerLevel sl)) return false;
        ItemStack fuel = m.getItem(ProgrammableFurnaceBlockEntity.SLOT_FUEL);
        if (fuel.isEmpty()) return false;
        FuelConfig.FuelAttrs attrs = FuelConfig.fuelAttrs(BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString());
        int vanillaTicks = burnDuration(sl, fuel);
        if (attrs == null && vanillaTicks <= 0) return false;   // 既不在配置里、原版也不可燃
        int maxP = 0;                 // 0 = 回退全局默认
        int perTick = 0;
        int burnTicks = FuelConfig.burnTimeDefault();
        double eff = FuelConfig.burnEfficiency();
        if (attrs != null) {
            burnTicks = attrs.burnTime();
            eff = attrs.efficiency() > 0 ? attrs.efficiency() : FuelConfig.burnEfficiency();
            maxP = attrs.maxProgress();
            perTick = attrs.progressPerTick();
        } else {
            // 配置外的原版燃料：燃时按物品自己的 COOKING_FUEL，进度参数沿用全局默认
            burnTicks = vanillaTicks;
        }
        // 燃烧优化卡：读取对应 boosts 档，与当前燃料逐项取"更好"（燃时/增量/效率取大，进度上限取小）
        int boost = m.boostTier();
        if (boost > 0) {
            FuelConfig.FuelAttrs b = FuelConfig.boostAttrs(boost);
            if (b != null) {
                burnTicks = Math.max(burnTicks, b.burnTime());
                eff = Math.max(eff, b.efficiency());
                perTick = Math.max(perTick, b.progressPerTick());
                maxP = maxP == 0 ? b.maxProgress() : Math.min(maxP, b.maxProgress());
            }
        }
        int dur = (int) Math.max(1, burnTicks * eff);
        if (dur <= 0) return false;
        fuel.shrink(1);
        if (fuel.isEmpty()) m.setItem(ProgrammableFurnaceBlockEntity.SLOT_FUEL, ItemStack.EMPTY);
        m.setBurnTime(dur);
        m.setBurnConfig(maxP, perTick);
        m.setChanged();
        return true;
    }

    /** 解析物品的原版燃时 tick（MC 26.3：CookingFuel 组件，需 LootContext 按容器位置解析）。 */
    private int burnDuration(ServerLevel sl, ItemStack fuel) {
        LootContext ctx = new LootContext.Builder(new LootParams.Builder(sl)
                .withParameter(LootContextParams.BLOCK_STATE, level.getBlockState(pos))
                .withParameter(LootContextParams.BLOCK_ENTITY, machine)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.CONTAINER, machine)
                .withParameter(NeoForgeLootContextParams.QUERIED_STACK, fuel)
                .create(LootContextParamSets.CONTAINER_PROCESS))
                .create(Optional.empty());
        return ResolvableInt.getFromItem(fuel, DataComponents.COOKING_FUEL, CookingFuel::burnTime, ctx, 0);
    }


    /**
     * furnace.result(输入物品) → 查 MC 烧炼配方(SmeltingRecipe) 返回产物 Item。
     * 产物经 SingleItemRecipe#assemble 由 ItemStackTemplate 生成（真实产物，非占位）；无匹配配方返回空 Item()。
     */
    private Object resultOf(ProgrammableFurnaceBlockEntity m, List<Object> args) {
        ItemStack input = toStack(unwrapArg(args, 0));
        if (input.isEmpty()) return new ExternalRef(ItemStack.EMPTY);
        if (!(level instanceof ServerLevel sl)) return new ExternalRef(ItemStack.EMPTY);
        SingleRecipeInput in = new SingleRecipeInput(input);
        Optional<RecipeHolder<SmeltingRecipe>> opt = sl.getServer().getRecipeManager().getRecipeFor(RecipeType.SMELTING, in, sl);
        if (opt.isEmpty()) return new ExternalRef(ItemStack.EMPTY);
        ItemStack out = opt.get().value().assemble(in);
        return new ExternalRef(out.isEmpty() ? ItemStack.EMPTY : out);
    }

    // ===================== Container（两套视图）=====================

    /**
     * setItem 严格语义：空物品 → 清空槽；槽空 → 放入；槽非空 → 返回失败（不覆盖、不合并）。
     */
    private boolean setExact(Container c, int i, ItemStack put) {
        if (put.isEmpty()) { c.setItem(i, ItemStack.EMPTY); c.setChanged(); return true; }
        if (!c.getItem(i).isEmpty()) return false;
        c.setItem(i, put.copy());
        c.setChanged();
        return true;
    }

    /**
     * add 合并语义：空物品 → 失败；槽空 → 放入；同物品未满 → 堆叠合并；不同物品或已满 → 失败。
     */
    private boolean addStack(Container c, int i, ItemStack put) {
        if (put.isEmpty()) return false;
        ItemStack cur = c.getItem(i);
        if (cur.isEmpty()) { c.setItem(i, put.copy()); c.setChanged(); return true; }
        if (!ItemStack.isSameItemSameComponents(cur, put)) return false;
        int space = cur.getMaxStackSize() - cur.getCount();
        int add = Math.min(space, put.getCount());
        if (add <= 0) return false;
        cur.grow(add);
        c.setChanged();
        return true;
    }

    private Object callContainer(Container c, String name, List<Object> args) {
        return switch (name) {
            case "getItems" -> itemList(c);
            case "setItems" -> setItems(c, args);
            case "isEmpty" -> c.isEmpty();
            case "slotSize" -> (long) c.getContainerSize();
            case "slots" -> slotGroup(allIndices(c), c);
            case "setSlots" -> setSlots(c, args);
            case "getItem" -> new ExternalRef(c.getItem(intArg(args, 0)));
            case "setItem" -> {
                int i = intArg(args, 0);
                yield setExact(c, i, toStack(unwrapArg(args, 1)));
            }
            case "add" -> {
                int i = intArg(args, 0);
                yield addStack(c, i, toStack(unwrapArg(args, 1)));
            }
            default -> throw notImplemented("Container 方法", c, name);
        };
    }

    /** getItems() → ExternalRef(vector<Item>)。 */
    private Object itemList(Container c) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            out.add(new ExternalRef(c.getItem(i)));
        }
        return new ExternalRef(out);
    }

    /** setItems(vector<Item>)：物品级移动，槽位不够则整体拒绝（不做部分放入）。 */
    private boolean setItems(Container c, List<Object> args) {
        List<Object> items = listArg(unwrapArg(args, 0));
        if (items.size() > c.getContainerSize()) return false;
        for (int i = 0; i < items.size(); i++) {
            c.setItem(i, toStack(unwrap(items.get(i))));
        }
        c.setChanged();
        return true;
    }

    /** setSlots(vector<Slot>)：槽位级移动，保留格子；同槽已有物品则整体拒绝。 */
    private boolean setSlots(Container c, List<Object> args) {
        List<Object> slots = listArg(unwrapArg(args, 0));
        if (slots.size() > c.getContainerSize()) return false;
        // 先检查全部目标槽可用（空 或 同一引用），任一冲突则整体拒绝
        for (int i = 0; i < slots.size(); i++) {
            SlotView sv = slotView(unwrap(slots.get(i)));
            ItemStack st = sv.container.getItem(sv.index);
            if (st.isEmpty()) continue;
            if (!c.getItem(i).isEmpty()) return false;
        }
        for (int i = 0; i < slots.size(); i++) {
            SlotView sv = slotView(unwrap(slots.get(i)));
            c.setItem(i, sv.container.getItem(sv.index).copy());
        }
        c.setChanged();
        return true;
    }

    // ===================== Slot =====================

    private Object callSlot(SlotView sv, String name, List<Object> args) {
        return switch (name) {
            case "getItem" -> new ExternalRef(sv.container.getItem(sv.index));
            case "setItem" -> setExact(sv.container, sv.index, toStack(unwrapArg(args, 0)));
            case "add" -> addStack(sv.container, sv.index, toStack(unwrapArg(args, 0)));
            case "isEmpty" -> sv.container.getItem(sv.index).isEmpty();
            case "getCount" -> (long) sv.container.getItem(sv.index).getCount();
            case "take" -> {   // 槽.take(数量)：从源槽拆出指定数量并返回（源槽减少；缺省 1）
                int n = args.isEmpty() ? 1 : intArg(args, 0);
                ItemStack st = sv.container.getItem(sv.index);
                if (st.isEmpty()) yield new ExternalRef(ItemStack.EMPTY);
                int take = Math.min(Math.max(n, 1), st.getCount());
                ItemStack part = sv.container.removeItem(sv.index, take);
                sv.container.setChanged();
                yield new ExternalRef(part.isEmpty() ? ItemStack.EMPTY : part);
            }
            // 槽.canPut(物品 | 物品类型 | "id")：这个槽收不收它（问的是容器层 canPlaceItem，和漏斗同一条规则）
            case "canPut" -> canPut(sv, args.isEmpty() ? null : unwrapArg(args, 0));
            default -> throw notImplemented("Slot 方法", sv, name);
        };
    }

    // ===================== ItemType（物品类型，不带头数）=====================
    /** 脚本侧的物品类型：只有 id，没有数量。`ItemType("minecraft:coal")` 造出来，用来问"这个槽收不收它"。 */
    public record ItemTypeValue(Identifier id) {
        static ItemTypeValue of(String raw) {
            return new ItemTypeValue(Identifier.tryParse(ns(raw)) == null ? Identifier.parse("minecraft:air") : Identifier.parse(ns(raw)));
        }

        public boolean isEmpty() {
            return id.equals(Identifier.parse("minecraft:air"));
        }
    }

    private Object callItemType(ItemTypeValue t, String name, List<Object> args) {
        return switch (name) {
            case "is" -> t.id().equals(Identifier.tryParse(ns(strArg(args, 0))) == null ? t.id() : Identifier.parse(ns(strArg(args, 0))));
            case "isEmpty" -> t.isEmpty();
            case "getId" -> t.id().toString();
            default -> throw notImplemented("ItemType 方法", t, name);
        };
    }

    // ===================== Item（ItemStack）=====================

    private Object callItem(ItemStack st, String name, List<Object> args) {
        return switch (name) {
            case "getCount" -> (long) st.getCount();
            case "isEmpty" -> st.isEmpty();
            case "getMaxStackSize" -> (long) st.getMaxStackSize();
            case "hasTag" -> st.is(itemTag(strArg(args, 0)));
            case "is" -> st.is(h -> h.unwrapKey()
                    .map(k -> k.identifier().equals(Identifier.parse(ns(strArg(args, 0)))))
                    .orElse(false));   // 按物品 id 匹配（如 minecraft:raw_iron）
            default -> throw notImplemented("Item 方法", st, name);
        };
    }

    // ===================== List（vector 通用操作）=====================

    private Object callList(List<?> list, String name, List<Object> args) {
        return switch (name) {
            case "size" -> (long) list.size();
            case "isEmpty" -> list.isEmpty();
            default -> throw notImplemented("vector 方法", list, name);
        };
    }

    // ===================== ItemStream：输入 → 缓存 → 输出 =====================
    // 三段式状态机（草稿 §0 F7 / F7.1）：advance() 每次最多推一步，先往输出塞、再从输入补进缓存。
    // 缓存容量固定（构造时给），所以"上游一直在产、下游满着"时缓存会满、advance() 返回 false —— 脚本据此退避。

    private Object callStream(ItemStreamState s, String name, List<Object> args) {
        switch (name) {
            case "init": {
                s.in = listArg(args.size() > 0 ? unwrapArg(args, 0) : null);
                s.out = listArg(args.size() > 1 ? unwrapArg(args, 1) : null);
                s.tag = args.size() >= 3 ? strArg(args, 2) : null;
                s.buffer.clear();
                return Boolean.TRUE;
            }
            case "advance": {
                // 一次 advance 只推一步：先把缓存送出去（输出满了就卡在缓存里），再补一件进缓存。
                boolean movedOut = flushBuffer(s);
                boolean movedIn = pullIntoBuffer(s);
                touchContainers(s);
                return movedOut || movedIn;
            }
            case "hasNext": {
                // 缓存里有货（等输出），或者输入里还有能收的物品
                if (!s.buffer.isEmpty()) return Boolean.TRUE;
                return hasMatch(s.in, s.tag);
            }
            case "size": return (long) s.bufferedCount();
            case "capacity": return (long) s.capacity;
            case "isEmpty": return s.buffer.isEmpty();
            case "clear": {
                s.buffer.clear();
                return Boolean.TRUE;
            }
            case "setTag": {
                s.tag = args.isEmpty() ? null : strArg(args, 0);
                return Boolean.TRUE;
            }
            default: throw notImplemented("ItemStream 方法", s, name);
        }
    }

    /** 把缓存里的物品尽量塞进输出端容器（同物品可叠）。返回是否真的移动了物品。 */
    private boolean flushBuffer(ItemStreamState s) {
        if (s.out == null || s.out.isEmpty() || s.buffer.isEmpty()) return false;
        boolean moved = false;
        for (int i = 0; i < s.buffer.size(); ) {
            ItemStack remain = pushToOutput(s.out, s.buffer.get(i));
            if (remain.isEmpty()) {
                s.buffer.remove(i);
                moved = true;
            } else if (remain.getCount() != s.buffer.get(i).getCount()) {
                s.buffer.set(i, remain);
                moved = true;
                i++;
            } else {
                i++;   // 一点也没塞进去
            }
        }
        return moved;
    }

    /** 从输入端搬"一件"进缓存（按容器 → 槽位顺序；同物品叠进已有堆）。返回是否搬动。 */
    private boolean pullIntoBuffer(ItemStreamState s) {
        if (s.in == null || s.in.isEmpty() || !s.bufferHasRoom()) return false;
        for (Object inObj : s.in) {
            if (!(unwrap(inObj) instanceof Container inC)) continue;
            for (int i = 0; i < inC.getContainerSize(); i++) {
                ItemStack st = inC.getItem(i);
                if (st.isEmpty()) continue;
                if (s.tag != null && !st.is(itemTag(s.tag))) continue;
                int room = s.bufferRoom();
                if (room <= 0) return false;
                int take = Math.min(room, st.getCount());
                ItemStack moved = st.copyWithCount(take);
                int left = st.getCount() - take;
                inC.setItem(i, left <= 0 ? ItemStack.EMPTY : st.copyWithCount(left));
                s.insert(moved);
                return true;
            }
        }
        return false;
    }

    /**
     * 把一叠物品塞进输出端容器：先叠到同类堆，再占空槽；尊重 {@code canPlaceItem}（与漏斗同一条规则）。
     * @return 没塞完的剩余（全部塞完返回 {@link ItemStack#EMPTY}）
     */
    private ItemStack pushToOutput(List<Object> outs, ItemStack stack) {
        ItemStack remain = stack;
        for (Object outObj : outs) {
            if (!(unwrap(outObj) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (remain.isEmpty()) return ItemStack.EMPTY;
                ItemStack slot = c.getItem(i);
                if (slot.isEmpty()) {
                    if (!c.canPlaceItem(i, remain.copyWithCount(1))) continue;
                    int fit = Math.min(remain.getCount(), remain.getMaxStackSize());
                    c.setItem(i, remain.copyWithCount(fit));
                    remain = remain.getCount() > fit ? remain.copyWithCount(remain.getCount() - fit) : ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(slot, remain)) {
                    int space = slot.getMaxStackSize() - slot.getCount();
                    if (space <= 0) continue;
                    int fit = Math.min(space, remain.getCount());
                    c.setItem(i, slot.copyWithCount(slot.getCount() + fit));
                    remain = remain.getCount() > fit ? remain.copyWithCount(remain.getCount() - fit) : ItemStack.EMPTY;
                }
            }
        }
        return remain;
    }

    private void touchContainers(ItemStreamState s) {
        for (List<Object> list : java.util.Arrays.asList(s.in, s.out)) {
            if (list == null) continue;
            for (Object obj : list) {
                if (unwrap(obj) instanceof Container c) c.setChanged();
            }
        }
    }

    private boolean hasMatch(List<Object> containers, String tag) {
        if (containers == null) return false;
        for (Object obj : containers) {
            if (!(unwrap(obj) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty()) continue;
                if (tag == null || st.is(itemTag(tag))) return true;
            }
        }
        return false;
    }

    // ===================== 槽组构造 =====================

    /** 槽组 → ExternalRef(vector<Slot>)。owner 决定槽归属（熔炉槽组传 machine，容器 .slots 传该容器）。 */
    private Object slotGroup(List<Integer> indices, Container owner) {
        List<Object> out = new ArrayList<>(indices.size());
        for (int i : indices) out.add(new ExternalRef(new SlotView(owner, i)));
        return new ExternalRef(out);
    }

    private List<Integer> allIndices(Container c) {
        List<Integer> idx = new ArrayList<>(c.getContainerSize());
        for (int i = 0; i < c.getContainerSize(); i++) idx.add(i);
        return idx;
    }

    // ===================== 范围 / 辅助 =====================

    private Scope resolveScope(List<Object> args, int scopeIdx) {
        Object s = unwrapArg(args, scopeIdx);
        if (s instanceof Scope sc) return sc;
        // 缺省：以自身为中心半径 16
        return new Scope(pos.offset(-16, -16, -16), pos.offset(16, 16, 16));
    }

    private Scope radiusScope(List<Object> args) {
        BlockPos p = blockPosArg(unwrapArg(args, 0));
        int r = args.size() >= 2 && unwrapArg(args, 1) instanceof Number n ? n.intValue() : 16;
        return new Scope(p.offset(-r, -r, -r), p.offset(r, r, r));
    }

    private Scope boxScope(List<Object> args) {
        return new Scope(blockPosArg(unwrapArg(args, 0)), blockPosArg(unwrapArg(args, 1)));
    }

    private Iterable<BlockPos> each(Scope s) {
        return BlockPos.betweenClosed(s.min, s.max);
    }

    // ===================== 值 / 类型转换 =====================

    private static Object unwrap(Object o) {
        return o instanceof ExternalRef e ? e.delegate() : o;
    }

    private static Object unwrapArg(List<Object> args, int i) {
        return i < args.size() ? unwrap(args.get(i)) : null;
    }

    private static String strArg(List<Object> args, int i) {
        Object o = unwrapArg(args, i);
        return o == null ? "" : String.valueOf(o);
    }

    private static int intArg(List<Object> args, int i) {
        Object o = unwrapArg(args, i);
        if (!(o instanceof Number n)) throw new RuntimeException("参数不是整数: " + o);
        return n.intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listArg(Object o) {
        if (o instanceof List<?> l) return (List<Object>) l;
        throw new RuntimeException("参数不是列表: " + o);
    }

    private static SlotView slotView(Object o) {
        if (o instanceof SlotView sv) return sv;
        throw new RuntimeException("参数不是槽位(Slot): " + o);
    }

    private static BlockPos blockPosArg(Object o) {
        if (o instanceof BlockPos p) return p;
        throw new RuntimeException("参数不是坐标: " + o);
    }

    /** 把运行期值转成 MC ItemStack（外部物品句柄或空）。 */
    private static ItemStack toStack(Object o) {
        if (o == null) return ItemStack.EMPTY;
        if (o instanceof ItemStack st) return st;
        if (o instanceof SlotView sv) return sv.container.getItem(sv.index);
        throw new RuntimeException("不能转换为物品(Item): " + o);
    }

    private static String ns(String name) {
        // 标签::燃料 已由运行器在成员求值时转成 "燃料"；此处保证带默认命名空间 minecraft
        return name.contains(":") ? name : "minecraft:" + name;
    }

    private static TagKey<net.minecraft.world.item.Item> itemTag(String name) {
        return TagKey.create(Registries.ITEM, Identifier.parse(ns(name)));
    }

    /** `Item()` → 空物品；`Item("minecraft:coal")` → 该物品一个（查不到的 id 给空物品，不抛）。 */
    private static ItemStack itemValue(List<Object> args) {
        if (args.isEmpty()) return ItemStack.EMPTY;
        return stackOfType(strArg(args, 0), 1);
    }

    /** 按 id 造一个 ItemStack；id 非法或物品不存在时返回空栈（脚本拼错 id 不该让整个 tick 崩掉）。 */
    private static ItemStack stackOfType(String raw, int count) {
        Identifier id = Identifier.tryParse(ns(raw));
        if (id == null) return ItemStack.EMPTY;
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(id)
                .map(net.minecraft.core.Holder.Reference::value)
                .orElse(net.minecraft.world.item.Items.AIR);
        if (item == net.minecraft.world.item.Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, Math.max(1, count));
    }

    /** 把脚本值（ItemStack / ItemType / "id"）转成"能不能放进这个槽"的答案。 */
    private static boolean canPut(SlotView sv, Object raw) {
        ItemStack probe;
        if (raw instanceof ItemTypeValue t) {
            probe = t.isEmpty() ? ItemStack.EMPTY : stackOfType(t.id().toString(), 1);
        } else if (raw instanceof ItemStack st) {
            probe = st;
        } else if (raw instanceof String s) {
            probe = stackOfType(s, 1);
        } else {
            probe = ItemStack.EMPTY;
        }
        return !probe.isEmpty() && sv.container.canPlaceItem(sv.index, probe);
    }

    private RuntimeException notImplemented(String what, Object on, String name) {
        return new RuntimeException("未实现: " + what + " '" + name + "' on " + on);
    }

    // ===================== 内部类型 =====================

    /** 槽位句柄：容器 + 槽索引（语言 Slot）。 */
    record SlotView(Container container, int index) {}

    /** 搜索范围（半径/区域统一为 box）。 */
    record Scope(BlockPos min, BlockPos max) {}

    /** ItemStream 状态：输入端、缓存、输出端（真状态机，草稿 §0 F7.1）。 */
    static final class ItemStreamState {
        /** 默认缓存容量（件）：够一条"上游生产 - 下游消费"的缓冲带，又不至于吃掉整箱内存。 */
        static final int DEFAULT_CAPACITY = 64;

        final int capacity;
        final List<ItemStack> buffer = new java.util.ArrayList<>();
        List<Object> in;
        List<Object> out;
        String tag;

        ItemStreamState() { this(DEFAULT_CAPACITY); }

        ItemStreamState(int capacity) { this.capacity = Math.max(1, capacity); }

        int bufferedCount() {
            int n = 0;
            for (ItemStack st : buffer) n += st.getCount();
            return n;
        }

        boolean bufferHasRoom() { return bufferedCount() < capacity; }

        int bufferRoom() { return Math.max(0, capacity - bufferedCount()); }

        /** 同物品叠进已有堆，否则新开一堆。容量由调用方先检查。 */
        void insert(ItemStack st) {
            for (int i = 0; i < buffer.size(); i++) {
                ItemStack cur = buffer.get(i);
                if (!ItemStack.isSameItemSameComponents(cur, st)) continue;
                int space = cur.getMaxStackSize() - cur.getCount();
                if (space <= 0) continue;
                int fit = Math.min(space, st.getCount());
                buffer.set(i, cur.copyWithCount(cur.getCount() + fit));
                if (fit == st.getCount()) return;
                st = st.copyWithCount(st.getCount() - fit);
            }
            buffer.add(st);
        }
    }
}
