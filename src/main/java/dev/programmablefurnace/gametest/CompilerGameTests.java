package dev.programmablefurnace.gametest;

import dev.programmablefurnace.furnace.FurnaceRegistration;
import dev.programmablefurnace.compiler.CompilerRegistration;
import dev.programmablefurnace.item.ModItems;
import dev.programmablefurnace.base.ModLog;
import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.compiler.CompilerBlockEntity;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 编译器 GameTest（集成测试，在 gameTestServer 内真实运行）。
 * <p>每个 {@code static void xxx(GameTestHelper)} 即为一个可注册的测试函数，
 * 由 {@link ProgrammableFurnaceMod#TEST_FUNCTIONS} 注册到 MC 的 TEST_FUNCTION 注册表，
 * 再由 datagen 生成的 test_instance 引用。</p>
 * <p>分两类：前四个是注册/常量冒烟；后两个（{@code compilerCompilesSource} /
 * {@code compilerReportsCompileErrors}）在真实服务端把源码放进输入槽跑 {@code tryCompile}，
 * 断言"产物状态 + 文件类型 + 输入被消耗 / 失败带 compile_errors"——这是编译器整条链路唯一被
 * 自动化跑过的地方（{@code src/test} 里的单测逐个阶段验证，但不碰方块实体）。</p>
 */
public final class CompilerGameTests {

    private CompilerGameTests() {}

    /** 冒烟：编译器方块与三种文件物品均已在真实服务器注册。 */
    public static void compileExecutable(GameTestHelper helper) {
        helper.assertTrue(CompilerRegistration.BLOCK.get() != null,
                "编译器方块未注册");
        helper.assertTrue(ModItems.CODE_FILE_BLANK.get() != null,
                "空白文件物品未注册");
        helper.assertTrue(ModItems.CODE_FILE_EXEC.get() != null,
                "可执行文件物品未注册");
        helper.assertTrue(ModItems.CODE_FILE_FAILED.get() != null,
                "编译失败文件物品未注册");
        helper.succeed();
    }

    /** 冒烟：方块实体槽位常量符合编译器容器约定（编译输入/输出 + 依赖库输入/输出 + 依赖库起始）。 */
    public static void compileFailed(GameTestHelper helper) {
        helper.assertTrue(CompilerBlockEntity.SLOT_INPUT == 0,
                "编译输入槽应为 0，实际: " + CompilerBlockEntity.SLOT_INPUT);
        helper.assertTrue(CompilerBlockEntity.SLOT_OUTPUT == 1,
                "编译输出槽应为 1，实际: " + CompilerBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(CompilerBlockEntity.SLOT_BATCH_INPUT == 2,
                "依赖库输入槽应为 2，实际: " + CompilerBlockEntity.SLOT_BATCH_INPUT);
        helper.assertTrue(CompilerBlockEntity.SLOT_BATCH_OUTPUT == 3,
                "依赖库输出槽应为 3，实际: " + CompilerBlockEntity.SLOT_BATCH_OUTPUT);
        helper.assertTrue(CompilerBlockEntity.BATCH_START == 4,
                "依赖库区起始槽应为 4，实际: " + CompilerBlockEntity.BATCH_START);
        helper.succeed();
    }

    /** 冒烟：默认依赖库容量大于 0，且依赖库区槽位数量与容量一致。 */
    public static void compileOutputBlocked(GameTestHelper helper) {
        int cap = CompilerBlockEntity.configBatchCapacity();
        helper.assertTrue(cap > 0, "默认依赖库容量应为正数，实际: " + cap);
        helper.assertTrue(CompilerBlockEntity.BATCH_START + cap <= 256,
                "依赖库区槽位越界，start=" + CompilerBlockEntity.BATCH_START + " cap=" + cap);
        helper.succeed();
    }

    /** 冒烟：CodeStorage 在真实服务器可正常存取编译代码（编译流程的数据载体）。 */
    public static void batchMove(GameTestHelper helper) {
        net.minecraft.world.item.ItemStack s =
                new net.minecraft.world.item.ItemStack(ModItems.CODE_FILE_BLANK.get());
        CodeStorage.setCode(s, "set 5");
        helper.assertTrue("set 5".equals(CodeStorage.getCode(s)),
                "CodeStorage 存取失败，实际: " + CodeStorage.getCode(s));
        helper.assertTrue(CodeStorage.hasCode(s), "CodeStorage.hasCode 应返回 true");

        // 空白 = 无组件（规范形）：编译空输入产出的空白必须与合成的裸空白逐组件相同，否则原版不会合并
        net.minecraft.world.item.ItemStack blank =
                new net.minecraft.world.item.ItemStack(ModItems.CODE_FILE_BLANK.get());
        CodeStorage.setCode(blank, "");
        CodeStorage.setContentState(blank, dev.programmablefurnace.base.ContentState.BLANK);
        net.minecraft.world.item.ItemStack crafted =
                new net.minecraft.world.item.ItemStack(ModItems.CODE_FILE_BLANK.get());
        helper.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(blank, crafted),
                "空白文件不应携带任何组件（组件不等会导致无法堆叠），实际: " + blank.getComponents());
        helper.succeed();
    }

    /** 能被编译成可执行文件的真实源码（含 RunLoop，且不依赖任何游戏对象）。 */
    private static final String GOOD_SOURCE = """
            MODULE("测试")
            int 平方(int x) {
                return x * x;
            }
            void RunLoop(int tick) {
                int a = 平方(3);
            }
            """;

    /** 编译器真实链路：输入槽放源码 → `tryCompile` → 输出槽得到可执行的 RUNTIME 文件。 */
    public static void compilerCompilesSource(GameTestHelper helper) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(1, 1, 1);
        helper.setBlock(pos, CompilerRegistration.BLOCK.get());
        CompilerBlockEntity be = helper.getBlockEntity(pos, CompilerBlockEntity.class);

        net.minecraft.world.item.ItemStack in =
                new net.minecraft.world.item.ItemStack(ModItems.CODE_FILE_BLANK.get());
        dev.programmablefurnace.base.CodeStorage.setCode(in, GOOD_SOURCE);
        be.setItem(CompilerBlockEntity.SLOT_INPUT, in);

        java.util.List<String> errors = be.tryCompile(GOOD_SOURCE);
        helper.assertTrue(errors.isEmpty(), "好程序不该有错误，实际: " + errors);

        net.minecraft.world.item.ItemStack out = be.getItem(CompilerBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(!out.isEmpty(), "输出槽应有编译产物");
        helper.assertTrue(out.getItem() instanceof dev.programmablefurnace.item.CodeFileItem,
                "产物应是代码文件，实际: " + out);
        helper.assertTrue(
                dev.programmablefurnace.base.CodeStorage.getContentState(out)
                        == dev.programmablefurnace.base.ContentState.EXECUTABLE,
                "产物状态应为 EXECUTABLE，实际: "
                        + dev.programmablefurnace.base.CodeStorage.getContentState(out));
        helper.assertTrue(
                dev.programmablefurnace.base.CodeStorage.getFileType(out)
                        == dev.programmablefurnace.base.FileType.RUNTIME,
                "产物文件类型应为 RUNTIME，实际: "
                        + dev.programmablefurnace.base.CodeStorage.getFileType(out));
        helper.assertTrue(be.getItem(CompilerBlockEntity.SLOT_INPUT).isEmpty(),
                "编译后输入文件应被消耗");

        // 空输入编译：产物必须是"裸空白"，与工作台合成的空白文件能堆叠（用户报告的 bug）
        be.setItem(CompilerBlockEntity.SLOT_OUTPUT, net.minecraft.world.item.ItemStack.EMPTY);
        net.minecraft.world.item.ItemStack blankIn =
                new net.minecraft.world.item.ItemStack(ModItems.CODE_FILE_BLANK.get());
        be.setItem(CompilerBlockEntity.SLOT_INPUT, blankIn);
        java.util.List<String> blankErrors = be.tryCompile("");
        helper.assertTrue(blankErrors.isEmpty(), "空输入不该报错，实际: " + blankErrors);
        net.minecraft.world.item.ItemStack blankOut = be.getItem(CompilerBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(!blankOut.isEmpty(), "空输入也要产出空白文件");
        net.minecraft.world.item.ItemStack craftedBlank =
                new net.minecraft.world.item.ItemStack(ModItems.CODE_FILE_BLANK.get());
        helper.assertTrue(
                net.minecraft.world.item.ItemStack.isSameItemSameComponents(blankOut, craftedBlank),
                "空白产物应与合成空白逐组件相同，实际组件: " + blankOut.getComponents());
        ModLog.LOG.info("[gametest] 编译器链路跑通：产物={} 状态={} 类型={}",
                out.getItem().getDescriptionId(),
                dev.programmablefurnace.base.CodeStorage.getContentState(out),
                dev.programmablefurnace.base.CodeStorage.getFileType(out));
        helper.succeed();
    }

    /** 编译失败的真实链路：坏源码 → 产物是 FAILED 文件，且错误挂在 `compile_errors` tag 上。 */
    public static void compilerReportsCompileErrors(GameTestHelper helper) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(1, 1, 1);
        helper.setBlock(pos, CompilerRegistration.BLOCK.get());
        CompilerBlockEntity be = helper.getBlockEntity(pos, CompilerBlockEntity.class);

        String bad = "MODULE(\"坏\")\nvoid RunLoop(int tick) {\n    & (1);\n}\n";
        net.minecraft.world.item.ItemStack in =
                new net.minecraft.world.item.ItemStack(ModItems.CODE_FILE_BLANK.get());
        dev.programmablefurnace.base.CodeStorage.setCode(in, bad);
        be.setItem(CompilerBlockEntity.SLOT_INPUT, in);

        java.util.List<String> errors = be.tryCompile(bad);
        helper.assertTrue(!errors.isEmpty(), "坏程序应该报错");

        net.minecraft.world.item.ItemStack out = be.getItem(CompilerBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(!out.isEmpty(), "失败也要产出文件（玩家要能看到它）");
        helper.assertTrue(
                dev.programmablefurnace.base.CodeStorage.getContentState(out)
                        == dev.programmablefurnace.base.ContentState.FAILED,
                "产物状态应为 FAILED，实际: "
                        + dev.programmablefurnace.base.CodeStorage.getContentState(out));
        helper.assertTrue(
                !dev.programmablefurnace.base.CodeStorage.getCompileErrors(out).isEmpty(),
                "错误应写进 compile_errors tag");
        ModLog.LOG.info("[gametest] 编译失败链路跑通：{} 条错误，第一条={}",
                errors.size(), errors.get(0));
        helper.succeed();
    }
}
