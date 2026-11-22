package dev.programmablefurnace.compiler.exec;

import dev.programmablefurnace.base.CompilerAnalyzer;
import dev.programmablefurnace.base.CompilerAnalyzer.ModuleSource;
import dev.programmablefurnace.base.ContentState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：多模块源码 → 编译 → 执行器驱动一个"假熔炉"，全程不碰 Minecraft。
 *
 * <p>这是本仓库第一条真正跑通"用户写的程序"的测试：它把 {@link CompilerAnalyzer#compileModules}
 * 与 {@link Executor} 接起来，用一个手写的 {@link Env} 假扮 {@code FurnaceEnv}。之所以能这样测，
 * 是因为解释器层（lex/syntax/sem/exec）本来就没有 MC 依赖——游戏侧只有 {@code FurnaceEnv} 那一层胶水。
 * 每一刻的中间状态都打到 stdout，便于排查"程序为什么没产出"。</p>
 */
class FurnaceProgramTest {

    /** 假熔炉：4 个输入槽各有 2 件料，推进 3 刻产出 1 件，燃料无限。 */
    static final class FakeFurnace implements Env {

        private static final int SLOTS = 4;
        private static final int TICKS_PER_ITEM = 3;

        private final Object furnace = new Object();
        private final List<SlotView> inputs = new ArrayList<>();
        private final List<SlotView> outputs = new ArrayList<>();
        private final List<Object> produced = new ArrayList<>();
        private final int[] progress = new int[SLOTS];

        long now;
        int ignites;

        FakeFurnace() {
            for (int i = 0; i < SLOTS; i++) {
                inputs.add(new SlotView(i, 2));
                outputs.add(new SlotView(i, 0));
            }
        }

        /** 槽视图：脚本眼里的 furnace.input[i] / furnace.output[i]。 */
        private static final class SlotView {
            final int index;
            int count;

            SlotView(int index, int count) {
                this.index = index;
                this.count = count;
            }
        }

        @Override
        public Object call(String name, Object target, List<Object> args) {
            if (target == furnace) {
                return switch (name) {
                    case "ignite" -> {
                        ignites++;
                        yield Boolean.TRUE;   // 燃料无限
                    }
                    case "advance" -> {
                        int slot = (int) (long) (Long) args.get(0);
                        progress[slot]++;
                        boolean done = progress[slot] >= TICKS_PER_ITEM;
                        if (done) progress[slot] = 0;
                        System.out.println("[fake] advance slot=" + slot + " -> " + done);
                        yield done;
                    }
                    case "setProgress" -> {
                        progress[(int) (long) (Long) args.get(0)] = (int) (long) (Long) args.get(1);
                        yield null;
                    }
                    default -> throw new IllegalStateException("furnace 上没有 '" + name + "'");
                };
            }
            if (target instanceof SlotView slot) {
                return switch (name) {
                    case "getCount" -> (long) slot.count;
                    case "take" -> {
                        int n = (int) (long) (Long) args.get(0);
                        int taken = Math.min(n, slot.count);
                        slot.count -= taken;
                        yield (long) taken;
                    }
                    case "add" -> {
                        if (slot.index >= SLOTS) throw new IllegalStateException("输出槽越界");
                        produced.add(args.get(0));
                        slot.count++;
                        System.out.println("[fake] output[" + slot.index + "] += " + args.get(0)
                                + " (total " + produced.size() + ")");
                        yield Boolean.TRUE;
                    }
                    case "isEmpty" -> slot.count == 0;
                    default -> throw new IllegalStateException("槽上没有 '" + name + "'");
                };
            }
            throw new IllegalStateException("不支持的对象调用: " + name + " on " + target);
        }

        @Override
        public Object read(String name, Object target) {
            if (target == null) {
                // 全局裸名：执行器先问 hasExternal，再 read(name, null) 取对象
                if ("furnace".equals(name)) return furnace;
                throw new IllegalStateException("没有全局 '" + name + "'");
            }
            if (target == furnace) {
                return switch (name) {
                    case "input" -> inputs;
                    case "output" -> outputs;
                    case "inputCount" -> (long) SLOTS;
                    case "burning" -> Boolean.TRUE;
                    default -> throw new IllegalStateException("furnace 上没有成员 '" + name + "'");
                };
            }
            throw new IllegalStateException("不支持的对象读取: " + name + " on " + target);
        }

        @Override
        public boolean hasExternal(String name) {
            return "furnace".equals(name) || "self".equals(name);
        }

        @Override
        public void write(String name, Object target, Object value) {
            throw new IllegalStateException("假熔炉不支持成员写入: " + name);
        }

        @Override
        public List<?> iterable(Object obj) {
            return obj instanceof List<?> list ? list : List.of();
        }

        @Override
        public long gameTime() {
            return now;
        }
    }

    private static final String ENTRY = """
            MODULE("主")
            from 工具 import 熔炼;

            void RunLoop(int tick) {
                if (tick % 20 == 0) {
                    furnace.ignite();
                }
                for (int i = 0; i < furnace.inputCount; i = i + 1) {
                    if (furnace.input[i].getCount() > 0) {
                        if (furnace.advance(i)) {
                            furnace.output[i].add(熔炼());
                            furnace.input[i].take(1);
                            furnace.setProgress(i, 0);
                        }
                    }
                }
            }
            """;

    private static final String DEPENDENCY = """
            MODULE("工具")
            string 熔炼() { return "锭"; }
            """;

    @Test
    void twoModuleProgram_smeltsThroughCompiledThenExecuted() {
        var result = CompilerAnalyzer.compileModules(List.of(
                new ModuleSource("主", ENTRY),
                new ModuleSource("工具", DEPENDENCY)));

        assertThat(result.state())
                .as("程序应当编译为可执行文件，错误: %s", result.errors())
                .isEqualTo(ContentState.EXECUTABLE);
        assertThat(result.entryModule()).isEqualTo("主");
        assertThat(result.errors()).isEmpty();
        System.out.println("[test] 编译通过，模块=" + result.modules().size()
                + " 入口=" + result.entryModule() + " 合并声明=" + result.decls().size());

        FakeFurnace env = new FakeFurnace();
        Executor executor = Executor.of(result.decls(), result.table(), env);

        for (int t = 1; t <= 60; t++) {
            env.now = t;
            var r = executor.tick();
            assertThat(r.ok()).as("第 %s tick 执行失败: %s", t, r.message()).isTrue();
            if (t % 10 == 0) {
                System.out.println("[test] tick=" + t + " 产出=" + env.produced.size()
                        + " 点火=" + env.ignites);
            }
        }

        // 4 个槽各 2 件料，每 3 刻一件：远远够产完
        assertThat(env.produced).hasSizeGreaterThanOrEqualTo(4);
        assertThat(env.produced).containsOnly("锭");
        // 60 刻里每 20 刻点一次火 -> 第 20/40/60 刻，共 3 次
        assertThat(env.ignites).isEqualTo(3);
        assertThat(env.inputs).allSatisfy(slot -> assertThat(slot.count).isLessThan(2));
    }

    @Test
    void brokenDependency_reportsErrorsInsteadOfRunning() {
        var result = CompilerAnalyzer.compileModules(List.of(
                new ModuleSource("主", ENTRY),
                new ModuleSource("工具", "MODULE(\"工具\")\nstring 熔炼() { return 1; }\n")));

        assertThat(result.state()).isEqualTo(ContentState.FAILED);
        assertThat(result.hasErrors()).isTrue();
        System.out.println("[test] 依赖模块出错时状态为 FAILED，错误: " + result.errors());
        assertThat(result.errors().toString()).contains("返回类型不匹配");
    }
}
