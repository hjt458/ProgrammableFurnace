package dev.programmablefurnace.gametest;

import dev.programmablefurnace.base.ModInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * GameTest 测试函数注册表。
 *
 * <p>MC 26.3 的 GameTest 是数据驱动的：函数在这里注册成 registry 条目，再由
 * {@code data/<modid>/test_instance/*.json} 引用。两者少一个测试就不会跑（见规范第 55 条）。
 * 放在 {@code gametest} 包，入口类不再持有这些句柄。</p>
 */
public final class ModGameTests {

    private ModGameTests() {}

    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, ModInfo.MODID);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COMPILER_COMPILE_EXEC =
            TEST_FUNCTIONS.register("compiler_compile_exec", () -> CompilerGameTests::compileExecutable);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COMPILER_COMPILE_FAILED =
            TEST_FUNCTIONS.register("compiler_compile_failed", () -> CompilerGameTests::compileFailed);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COMPILER_COMPILE_OUTPUT_BLOCKED =
            TEST_FUNCTIONS.register("compiler_compile_output_blocked", () -> CompilerGameTests::compileOutputBlocked);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COMPILER_BATCH_MOVE =
            TEST_FUNCTIONS.register("compiler_batch_move", () -> CompilerGameTests::batchMove);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COMPILER_REAL_COMPILE =
            TEST_FUNCTIONS.register("compiler_real_compile", () -> CompilerGameTests::compilerCompilesSource);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COMPILER_REAL_FAILURE =
            TEST_FUNCTIONS.register("compiler_real_failure", () -> CompilerGameTests::compilerReportsCompileErrors);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FURNACE_SMELTS =
            TEST_FUNCTIONS.register("furnace_smelts_through_script", () -> FurnaceGameTests::furnaceSmeltsThroughScript);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FURNACE_SIGNAL =
            TEST_FUNCTIONS.register("furnace_signal_entry", () -> FurnaceGameTests::furnaceSignalEntryRunsOnRisingEdge);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FURNACE_CHEST_FUEL =
            TEST_FUNCTIONS.register("furnace_chest_fuel", () -> FurnaceGameTests::furnacePullsFuelFromChest);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FURNACE_ITEM_STREAM =
            TEST_FUNCTIONS.register("furnace_item_stream", () -> FurnaceGameTests::furnaceMovesItemsWithItemStream);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FURNACE_SEARCH =
            TEST_FUNCTIONS.register("furnace_search", () -> FurnaceGameTests::furnaceSearchesContainers);

    public static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }
}
