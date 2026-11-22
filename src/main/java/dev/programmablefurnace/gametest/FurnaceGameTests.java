package dev.programmablefurnace.gametest;

import dev.programmablefurnace.furnace.FurnaceRegistration;
import dev.programmablefurnace.compiler.CompilerRegistration;
import dev.programmablefurnace.item.ModItems;
import dev.programmablefurnace.base.ModLog;
import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.ContentState;
import dev.programmablefurnace.furnace.ProgrammableFurnaceBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 执行器 GameTest：在真实服务端里让熔炉跑一段脚本，验证 {@code FurnaceEnv} 那一层胶水。
 *
 * <p>为什么需要它：{@code src/test} 下的 290 个用例全在纯逻辑层（前端、解释器、配置、布局），
 * {@code FurnaceEnv} 的每个原语（点火、推进、产出、槽位搬运）此前一次都没有被自动化跑过——
 * 它们只在客户端里手点过。这里用一个真实方块实体 + 真实物品/配方把最短链路走通。</p>
 *
 * <p>脚本刻意压到最短：不用 {@code get(...)} 搜索容器（测试结构里没有箱子），直接从自己的输入槽取料。
 * 把进度预置到 199 再推进，是为了让一个用例在几十 tick 内跑完，而不是等满 200 tick。</p>
 */
public final class FurnaceGameTests {

    private FurnaceGameTests() {}

    /**
     * 脚本：点火 → 把 0 号输入槽的进度推到临界 → 推进完成后产出并扣料。
     * <p>{@code furnace.result(料)} 走真实熔炉配方（粗铁 → 铁锭），所以这条用例同时验证了
     * "脚本里能拿到真实配方产物"。</p>
     */
    private static final String PROGRAM = """
            MODULE("测试")
            void RunLoop(int tick) {
                furnace.ignite();
                furnace.setProgress(0, 199);
                if (furnace.advance(0)) {
                    furnace.output[0].add(furnace.result(furnace.input[0].getItem()));
                    furnace.input[0].take(1);
                    furnace.setProgress(0, 0);
                }
            }
            """;

    /** 真实服务端里跑完整链路：放方块 → 塞程序与料 → 开跑 → 断言产出铁锭。 */
    public static void furnaceSmeltsThroughScript(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, FurnaceRegistration.BLOCK.get());
        ProgrammableFurnaceBlockEntity be =
                helper.getBlockEntity(pos, ProgrammableFurnaceBlockEntity.class);

        be.setItem(ProgrammableFurnaceBlockEntity.SLOT_INPUT, new ItemStack(Items.RAW_IRON, 4));
        be.setItem(ProgrammableFurnaceBlockEntity.SLOT_FUEL, new ItemStack(Items.COAL, 4));

        ItemStack program = new ItemStack(ModItems.CODE_FILE_BLANK.get());
        CodeStorage.setCode(program, PROGRAM);
        CodeStorage.setContentState(program, ContentState.EXECUTABLE);
        be.setItem(ProgrammableFurnaceBlockEntity.DEP_START, program);

        helper.assertTrue(be.hasExecutableProgram(), "依赖库容器里应有可运行程序");
        be.setRunning(true);

        // 20 tick 才重建运行器（见 BE.tick），再给它几 tick 跑脚本
        helper.startSequence()
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(be.lastCompileErrors().isEmpty(),
                            "程序不该有编译错误，实际: " + be.lastCompileErrors());
                    ItemStack out = be.getItem(ProgrammableFurnaceBlockEntity.SLOT_OUTPUT);
                    helper.assertTrue(out.is(Items.IRON_INGOT),
                            "脚本应产出铁锭，实际输出槽: " + out);
                    helper.assertTrue(be.getBurnTime() > 0,
                            "点火后应有余火，实际 burnTime=" + be.getBurnTime());
                    ItemStack input = be.getItem(ProgrammableFurnaceBlockEntity.SLOT_INPUT);
                    helper.assertTrue(input.getCount() < 4,
                            "脚本应扣掉原料，实际仍有 " + input.getCount() + " 件");
                    ModLog.LOG.info(
                            "[gametest] 脚本跑通：输出={}x{} burnTime={} 输入剩 {}",
                            out.getItem().getDescriptionId(), out.getCount(),
                            be.getBurnTime(), input.getCount());
                })
                .thenSucceed();
    }

    /** 事件入口：红石上升沿执行一次 {@code RunLoop()}（无参版），与常驻版互不干扰。 */
    private static final String SIGNAL_PROGRAM = """
            MODULE("信号")
            void RunLoop() {
                furnace.setProgress(0, 42);
            }
            """;

    /** 放红石块制造上升沿，断言脚本在"有信号的那一 tick"跑了一次。 */
    public static void furnaceSignalEntryRunsOnRisingEdge(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, FurnaceRegistration.BLOCK.get());
        ProgrammableFurnaceBlockEntity be =
                helper.getBlockEntity(pos, ProgrammableFurnaceBlockEntity.class);

        ItemStack program = new ItemStack(ModItems.CODE_FILE_BLANK.get());
        CodeStorage.setCode(program, SIGNAL_PROGRAM);
        CodeStorage.setContentState(program, ContentState.EXECUTABLE);
        be.setItem(ProgrammableFurnaceBlockEntity.DEP_START, program);
        // 刻意不开跑：只验证事件入口，常驻入口保持静默
        helper.assertTrue(be.hasExecutableProgram(), "依赖库容器里应有可运行程序");

        helper.startSequence()
                // 先等运行器建好（每 20 tick 重建），否则上升沿来了也没有执行器可跑
                .thenIdle(25)
                .thenExecute(() -> {
                    helper.assertTrue(be.getProgress(0) == 0,
                            "无信号时事件入口不该跑，实际 progress=" + be.getProgress(0));
                    helper.setBlock(pos.above(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(be.getProgress(0) == 42,
                            "上升沿应让 RunLoop() 跑一次，实际 progress=" + be.getProgress(0));
                })
                .thenSucceed();
    }

    /**
     * 搜索 + 槽可放性 + 槽搬运：脚本用 {@code get("chest")} 找到旁边的箱子，
     * 用 {@code slots[0].canPut(...)} 问"这个槽收不收煤"（收 → 把进度写成 7 当布尔信号），
     * 再从箱子 {@code take(1)} 补进自己的燃料槽。
     *
     * <p>这条用例一次打过 {@code get()} 的 65³ 扫描、{@code Slot.canPut}（走容器层 canPlaceItem）、
     * {@code take}/{@code add} 三条原语；箱子给 8 个煤、燃料槽补到 4 个就停。</p>
     */
    private static final String CHEST_PROGRAM = """
            MODULE("搬煤")
            void RunLoop(int tick) {
                Container 箱 = get("chest");
                if (箱.slots[0].canPut(Item("minecraft:coal"))) {
                    furnace.setProgress(0, 7);
                }
                if (furnace.fuel[0].getCount() < 4) {
                    furnace.fuel[0].add(箱.slots[0].take(1));
                }
            }
            """;

    public static void furnacePullsFuelFromChest(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        BlockPos chestPos = new BlockPos(2, 1, 1);
        helper.setBlock(pos, FurnaceRegistration.BLOCK.get());
        helper.setBlock(chestPos, net.minecraft.world.level.block.Blocks.CHEST);
        ProgrammableFurnaceBlockEntity be =
                helper.getBlockEntity(pos, ProgrammableFurnaceBlockEntity.class);
        net.minecraft.world.level.block.entity.ChestBlockEntity chest =
                helper.getBlockEntity(chestPos, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COAL, 8));

        ItemStack program = new ItemStack(ModItems.CODE_FILE_BLANK.get());
        CodeStorage.setCode(program, CHEST_PROGRAM);
        CodeStorage.setContentState(program, ContentState.EXECUTABLE);
        be.setItem(ProgrammableFurnaceBlockEntity.DEP_START, program);
        helper.assertTrue(be.hasExecutableProgram(), "依赖库容器里应有可运行程序");
        be.setRunning(true);

        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(be.lastCompileErrors().isEmpty(),
                            "好程序不该有编译错误，实际: " + be.lastCompileErrors());
                    helper.assertTrue(be.getProgress(0) == 7,
                            "canPut 应返回 true（箱子收煤）→ progress 应为 7，实际: " + be.getProgress(0));
                    helper.assertTrue(be.getItem(ProgrammableFurnaceBlockEntity.SLOT_FUEL).getCount() == 4,
                            "燃料槽应被补到 4 个，实际: " + be.getItem(ProgrammableFurnaceBlockEntity.SLOT_FUEL).getCount());
                    helper.assertTrue(chest.getItem(0).getCount() == 4,
                            "箱子应剩 4 个煤，实际: " + chest.getItem(0).getCount());
                    ModLog.LOG.info(
                            "[gametest] get/canPut/take/add 跑通：燃料={} 箱子剩={} progress={}",
                            be.getItem(ProgrammableFurnaceBlockEntity.SLOT_FUEL).getCount(), chest.getItem(0).getCount(), be.getProgress(0));
                })
                .thenSucceed();
    }

    /**
     * {@code ItemStream} 真状态机 + 标签过滤：箱子（10 煤 + 5 泥土）→ 缓存（容量 2）→ 木桶。
     *
     * <p>脚本 {@code while (流.hasNext()) 流.advance()} 一次把能搬的都搬完——缓存活在流对象里，
     * 每次 tick 新建一个流等于每 tick 都从空缓存开始，所以"一趟搬完"才是正确用法。
     * {@code init(进, 出, "minecraft:coals")} 的标签过滤保证泥土留在箱子里；
     * {@code 流.capacity()} 写进进度，用来证明容量参数真的生效。</p>
     */
    private static final String STREAM_PROGRAM = """
            MODULE("搬货")
            void RunLoop(int tick) {
                if (tick % 20 == 0) {
                    ItemStream 流 = ItemStream(2);
                    furnace.setProgress(0, 流.capacity());
                    流.init(searchByName("chest"), searchByName("barrel"), "minecraft:coals");
                    while (流.hasNext()) {
                        流.advance();
                    }
                }
            }
            """;

    public static void furnaceMovesItemsWithItemStream(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        BlockPos chestPos = new BlockPos(2, 1, 1);
        BlockPos barrelPos = new BlockPos(3, 1, 1);
        helper.setBlock(pos, FurnaceRegistration.BLOCK.get());
        helper.setBlock(chestPos, net.minecraft.world.level.block.Blocks.CHEST);
        helper.setBlock(barrelPos, net.minecraft.world.level.block.Blocks.BARREL);
        ProgrammableFurnaceBlockEntity be =
                helper.getBlockEntity(pos, ProgrammableFurnaceBlockEntity.class);
        net.minecraft.world.level.block.entity.ChestBlockEntity chest =
                helper.getBlockEntity(chestPos, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        net.minecraft.world.level.block.entity.BarrelBlockEntity barrel =
                helper.getBlockEntity(barrelPos, net.minecraft.world.level.block.entity.BarrelBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COAL, 10));
        chest.setItem(1, new ItemStack(Items.DIRT, 5));

        ItemStack program = new ItemStack(ModItems.CODE_FILE_BLANK.get());
        CodeStorage.setCode(program, STREAM_PROGRAM);
        CodeStorage.setContentState(program, ContentState.EXECUTABLE);
        be.setItem(ProgrammableFurnaceBlockEntity.DEP_START, program);
        helper.assertTrue(be.hasExecutableProgram(), "依赖库容器里应有可运行程序");
        be.setRunning(true);

        helper.startSequence()
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(be.lastCompileErrors().isEmpty(),
                            "好程序不该有编译错误，实际: " + be.lastCompileErrors());
                    helper.assertTrue(be.getProgress(0) == 2,
                            "ItemStream(2) 的容量应为 2，实际: " + be.getProgress(0));
                    int coalInBarrel = countItem(barrel, Items.COAL);
                    int dirtInBarrel = countItem(barrel, Items.DIRT);
                    helper.assertTrue(coalInBarrel == 10,
                            "10 个煤应全部搬到木桶，实际: " + coalInBarrel);
                    helper.assertTrue(dirtInBarrel == 0,
                            "标签过滤后泥土不该被搬，实际: " + dirtInBarrel);
                    helper.assertTrue(countItem(chest, Items.DIRT) == 5,
                            "泥土应留在箱子，实际: " + countItem(chest, Items.DIRT));
                    helper.assertTrue(countItem(chest, Items.COAL) == 0,
                            "煤应被搬空，实际: " + countItem(chest, Items.COAL));
                    ModLog.LOG.info(
                            "[gametest] ItemStream 跑通：木桶煤={} 箱子泥土={} 容量={}",
                            coalInBarrel, countItem(chest, Items.DIRT), be.getProgress(0));
                })
                .thenSucceed();
    }

    /**
     * 世界搜索：{@code searchByTag} 与 {@code searchByName} 各返回容器列表。
     *
     * <p>三条断言刻意按"邻居结构会一起被搜到"来写：GameTest 把所有结构摆在同一个世界里，
     * 默认搜索半径 16 会扫到其它用例放的箱子/木桶，所以只断言下限（≥）而不是精确条数。
     * 第三条是反例：不存在的标签必须返回空列表而不是抛错。</p>
     *
     * <p>顺带记一条事实：原版**没有** {@code minecraft:chests} 这个方块标签（只有
     * {@code minecraft:copper_chests}），所以标签要用真实存在的（这里是
     * {@code minecraft:guarded_by_piglins}，它同时包含箱子与木桶）。</p>
     */
    private static final String SEARCH_PROGRAM = """
            MODULE("搜索")
            void RunLoop(int tick) {
                vector<vector> 猪 = searchByTag("chest", "minecraft:guarded_by_piglins");
                vector<vector> 桶 = searchByName("barrel");
                vector<vector> 空 = searchByTag("chest", "minecraft:no_such_tag");
                furnace.setProgress(0, 猪.size());
                furnace.setProgress(1, 桶.size());
                furnace.setProgress(2, 空.size());
            }
            """;

    public static void furnaceSearchesContainers(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, FurnaceRegistration.BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 1), net.minecraft.world.level.block.Blocks.CHEST);
        helper.setBlock(new BlockPos(2, 1, 2), net.minecraft.world.level.block.Blocks.CHEST);
        helper.setBlock(new BlockPos(3, 1, 1), net.minecraft.world.level.block.Blocks.BARREL);
        ProgrammableFurnaceBlockEntity be =
                helper.getBlockEntity(pos, ProgrammableFurnaceBlockEntity.class);

        ItemStack program = new ItemStack(ModItems.CODE_FILE_BLANK.get());
        CodeStorage.setCode(program, SEARCH_PROGRAM);
        CodeStorage.setContentState(program, ContentState.EXECUTABLE);
        be.setItem(ProgrammableFurnaceBlockEntity.DEP_START, program);
        helper.assertTrue(be.hasExecutableProgram(), "依赖库容器里应有可运行程序");
        be.setRunning(true);

        helper.startSequence()
                .thenIdle(30)
                .thenExecute(() -> {
                    helper.assertTrue(be.lastCompileErrors().isEmpty(),
                            "这条搜索程序应能编译通过，实际错误: " + be.lastCompileErrors());
                    helper.assertTrue(be.getProgress(0) >= 3,
                            "guarded_by_piglins 标签应至少搜到本用例的 2 箱 + 1 桶，实际: " + be.getProgress(0));
                    helper.assertTrue(be.getProgress(1) >= 1,
                            "searchByName('barrel') 应至少搜到本用例的木桶，实际: " + be.getProgress(1));
                    helper.assertTrue(be.getProgress(2) == 0,
                            "不存在的标签应返回空列表，实际: " + be.getProgress(2));
                    ModLog.LOG.info(
                            "[gametest] searchByTag/searchByName 跑通：标签命中={} 名字命中={} 未知标签={}",
                            be.getProgress(0), be.getProgress(1), be.getProgress(2));
                })
                .thenSucceed();
    }

    private static int countItem(net.minecraft.world.Container container, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack st = container.getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }
}
