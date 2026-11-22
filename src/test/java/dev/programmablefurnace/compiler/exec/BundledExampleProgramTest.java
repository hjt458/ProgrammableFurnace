package dev.programmablefurnace.compiler.exec;

import dev.programmablefurnace.base.ContentState;
import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.sem.StorageBuilder;
import dev.programmablefurnace.compiler.sem.StorageTable;
import dev.programmablefurnace.compiler.sem.SymbolBinder;
import dev.programmablefurnace.compiler.sem.TypeChecker;
import dev.programmablefurnace.compiler.syntax.Ast;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 随包示例脚本 {@code programmablefurnace:scripts/furnace_smelting.mc} 的端到端测试：脚本从旁边箱子取粗铁/煤，按当前布局 inputCount 逐输入槽独立补料、
 * 点火共享、各自推进进度并产出铁锭放回箱子。纯 JVM 伪熔炉 FakeFurnaceEnv 模拟 get("chest")/槽/配方/每槽进度。
 * <p>与 {@code src/main/resources/programmablefurnace/scripts/furnace_smelting.mc} 同脚本。</p>
 */
public final class BundledExampleProgramTest {

    /** 随包示例脚本：直接读 resources 里的那一份，避免测试里再抄一遍。 */
    static String loadExample() {
        try (var in = BundledExampleProgramTest.class.getResourceAsStream("/programmablefurnace/scripts/furnace_smelting.mc")) {
            if (in == null) throw new IllegalStateException("classpath 上没有随包示例脚本");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    record ItemData(String name, int count) {
        static ItemData of(String n, int c) { return new ItemData(n, c); }
        static ItemData empty() { return new ItemData(null, 0); }
        boolean isEmpty() { return name == null; }
    }
    record SlotToken(String group, int index) {}

    /** 伪环境：熔炉（input/output 各 4 槽，fuel 1 槽，212 布局激活 2 输入）+ 旁箱(chest) + 点火 + 配方 result。 */
    static final class FakeFurnaceEnv implements Env {
        final ItemData[] input  = { null, null, null, null };
        final ItemData[] fuel   = { null };
        final ItemData[] output = { null, null, null, null };
        final ItemData[] chest  = { ItemData.of("粗铁", 64), ItemData.of("粗铁", 64), ItemData.of("煤", 6), null, null, null };  // 旁箱 6 槽：双槽各 64 粗铁（212 双输入并行）+ 6 煤
        int burnTime = 0;
        final int[] progress = new int[4];            // 每输入槽独立进度
        int inputCount = 2;                            // 212 布局：激活 2 个输入槽
        static final int BURN = 200, MAX_PROGRESS = 200;
        final Object machine = new Object(), chestToken = new Object();
        int litCount = 0, producedCount = 0;

        void tick() { if (burnTime > 0) burnTime--; }

        @Override public boolean hasExternal(String name) { return "furnace".equals(name) || "self".equals(name); }

        @Override public Object read(String name, Object target) {
            Object t = target instanceof ExternalRef e ? e.delegate() : target;
            if (t == null) return "furnace".equals(name) ? new ExternalRef(machine) : null;
            if (t == machine) {
                return switch (name) {
                    case "burning" -> burnTime > 0;
                    case "burnTime" -> (long) burnTime;
                    case "progress" -> (long) progress[0];
                    case "inputCount" -> (long) inputCount;
                    case "input" -> slotGroup(input.length, "input");
                    case "fuel" -> slotGroup(fuel.length, "fuel");
                    case "output" -> slotGroup(output.length, "output");
                    default -> null;
                };
            }
            if (t == chestToken) {
                return switch (name) {
                    case "getItems" -> { List<Object> l = new ArrayList<>(); for (ItemData d : chest) l.add(new ExternalRef(d)); yield new ExternalRef(l); }
                    case "slots" -> slotGroup(chest.length, "chest");
                    default -> null;
                };
            }
            return null;
        }

        @Override public Object call(String name, Object target, List<Object> args) {
            Object t = target instanceof ExternalRef e ? e.delegate() : target;
            if (t == null) {
                if ("get".equals(name)) return "chest".equals(strArg(args, 0)) ? new ExternalRef(chestToken) : null;
                if ("Item".equals(name)) return new ExternalRef(ItemData.empty());
                return null;
            }
            if (t == machine) {
                if ("ignite".equals(name)) {
                    if (burnTime > 0) return false;
                    if (fuel[0] == null || fuel[0].isEmpty()) return false;
                    fuel[0] = fuel[0].count() > 1 ? ItemData.of(fuel[0].name(), fuel[0].count() - 1) : null;
                    burnTime = BURN; litCount++;
                    return true;
                }
                if ("result".equals(name)) {   // 配方查询：粗铁 → 铁锭
                    Object a = unwrapArg(args, 0);
                    ItemData in = a instanceof ItemData d ? d : null;
                    if (in != null && "粗铁".equals(in.name())) { producedCount++; return new ExternalRef(ItemData.of("铁锭", 1)); }
                    return new ExternalRef(ItemData.empty());
                }
                if ("setProgress".equals(name)) {   // 推进指定输入槽进度
                    int slot = intArg(args, 0);
                    if (slot >= 0 && slot < progress.length) progress[slot] = Math.max(0, Math.min(intArg(args, 1), MAX_PROGRESS));
                    return null;
                }
                if ("getProgress".equals(name)) {   // 读指定输入槽进度
                    int slot = intArg(args, 0);
                    return (long) (slot >= 0 && slot < progress.length ? progress[slot] : 0);
                }
                if ("advance".equals(name)) {   // 封装"燃烧+进度"：燃烧中才推进，返回是否达到上限
                    int slot = intArg(args, 0);
                    if (slot < 0 || slot >= progress.length || burnTime <= 0) return false;
                    int p = progress[slot] + 1;
                    progress[slot] = Math.max(0, Math.min(p, MAX_PROGRESS));
                    return p >= MAX_PROGRESS;
                }
                return null;
            }
            if (t == chestToken) {
                return switch (name) {
                    case "getItems" -> { List<Object> l = new ArrayList<>(); for (ItemData d : chest) l.add(new ExternalRef(d)); yield new ExternalRef(l); }
                    case "isEmpty" -> chestItem(0).isEmpty();
                    default -> null;
                };
            }
            if (t instanceof SlotToken st) return callSlot(st, name, args);
            if (t instanceof ItemData it) {
                return switch (name) {
                    case "isEmpty" -> it.isEmpty();
                    case "hasTag" -> tagHit(it, strArg(args, 0));
                    case "is" -> isId(it, strArg(args, 0));
                    default -> null;
                };
            }
            return null;
        }

        private boolean isId(ItemData it, String id) {
            if (it.isEmpty()) return false;
            String n = it.name();
            if (id.contains("raw_iron")) return "粗铁".equals(n);
            if (id.contains("charcoal")) return "煤".equals(n);
            if (id.contains("coal")) return "煤".equals(n);
            if (id.contains("iron_ingot")) return "铁锭".equals(n);
            return false;
        }

        private boolean tagHit(ItemData it, String tag) {
            if (it.isEmpty()) return false;
            if (tag.endsWith("raw_iron")) return "粗铁".equals(it.name());
            if (tag.endsWith("fuels")) return "煤".equals(it.name());
            return false;
        }

        private Object callSlot(SlotToken st, String name, List<Object> args) {
            return switch (name) {
                case "getItem" -> { ItemData v = itemAt(st); yield new ExternalRef(v == null ? ItemData.empty() : v); }
                case "setItem" -> {   // 严格设置：空物品清空；槽空放入；槽非空失败
                    Object a = unwrapArg(args, 0);
                    ItemData d = a instanceof ItemData x ? x : null;
                    if (d == null || d.isEmpty()) { setItem(st, null); yield Boolean.TRUE; }
                    ItemData cur = itemAt(st);
                    if (cur != null && !cur.isEmpty()) yield Boolean.FALSE;
                    setItem(st, d);
                    yield Boolean.TRUE;
                }
                case "add" -> {   // 合并放入：空物品失败；槽空放入；同物品堆叠；不同/满失败
                    Object a = unwrapArg(args, 0);
                    ItemData d = a instanceof ItemData x ? x : null;
                    if (d == null || d.isEmpty()) yield Boolean.FALSE;
                    ItemData cur = itemAt(st);
                    if (cur == null || cur.isEmpty()) { setItem(st, d); yield Boolean.TRUE; }
                    if (!cur.name().equals(d.name())) yield Boolean.FALSE;
                    int sum = Math.min(64, cur.count() + d.count());
                    if (sum <= cur.count()) yield Boolean.FALSE;
                    setItem(st, ItemData.of(cur.name(), sum));
                    yield Boolean.TRUE;
                }
                case "getCount" -> { ItemData v = itemAt(st); yield (long) (v == null || v.isEmpty() ? 0 : v.count()); }
                case "isEmpty" -> { ItemData v = itemAt(st); yield v == null || v.isEmpty(); }
                case "take" -> {   // 槽.take(数量)：从源槽拆出指定数量并返回（源槽减少；缺省 1）
                    int n = args.isEmpty() ? 1 : intArg(args, 0);
                    ItemData v = itemAt(st);
                    if (v == null || v.isEmpty()) yield new ExternalRef(ItemData.empty());
                    int take = Math.min(Math.max(n, 1), v.count());
                    setItem(st, v.count() - take > 0 ? ItemData.of(v.name(), v.count() - take) : null);
                    yield new ExternalRef(ItemData.of(v.name(), take));
                }
                default -> null;
            };
        }

        private ItemData itemAt(SlotToken st) {
            return switch (st.group()) {
                case "input" -> input[st.index()];
                case "fuel" -> fuel[st.index()];
                case "output" -> output[st.index()];
                case "chest" -> chest[st.index()];
                default -> null;
            };
        }
        private void setItem(SlotToken st, ItemData v) {
            switch (st.group()) {
                case "input" -> input[st.index()] = v;
                case "fuel" -> fuel[st.index()] = v;
                case "output" -> output[st.index()] = v;
                case "chest" -> chest[st.index()] = v;
            }
        }
        private Object slotGroup(int n, String group) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < n; i = i + 1) out.add(new ExternalRef(new SlotToken(group, i)));
            return new ExternalRef(out);
        }
        private ItemData chestItem(int i) { return chest[i]; }

        @Override public void write(String name, Object target, Object value) {
            Object t = target instanceof ExternalRef e ? e.delegate() : target;
            if (t == machine && "progress".equals(name) && value instanceof Number n) {
                progress[0] = Math.max(0, Math.min(n.intValue(), MAX_PROGRESS));
            }
        }
        @Override public List<?> iterable(Object obj) {
            Object t = obj instanceof ExternalRef e ? e.delegate() : obj;
            if (t instanceof List<?> l) return l;
            return (List<?>) obj;
        }
        @Override public long gameTime() { return 0L; }
        private static String strArg(List<Object> args, int i) { return i < args.size() ? String.valueOf(args.get(i)) : ""; }
        private static int intArg(List<Object> args, int i) {
            Object o = i < args.size() ? (args.get(i) instanceof ExternalRef e ? e.delegate() : args.get(i)) : null;
            return o instanceof Number n ? n.intValue() : 0;
        }
        private static Object unwrapArg(List<Object> args, int i) { return i < args.size() ? (args.get(i) instanceof ExternalRef e ? e.delegate() : args.get(i)) : null; }
    }

    /** 编译并执行多输入旁箱烧炼脚本；断言脚本语法可编译、2 个输入槽并行各产出铁锭。 */
    @Test
    void bundledExampleScript_smeltsTwoIngotsThroughChest() {
        String src = loadExample();
        System.out.println("=== 随包示例脚本 furnace_smelting.mc 端到端执行 ===");
        System.out.println(src);

        Lexer lx = new Lexer("冶炼.mc", src);
        List<Token> tokens = lx.tokenize();
        Parser ps = new Parser(tokens);
        List<Ast.Decl> decls = ps.parseFile();
        StorageTable table = StorageBuilder.build(decls);
        List<CompileError> errors = new ArrayList<>(lx.errors());
        errors.addAll(ps.errors()); errors.addAll(table.errors());
        errors.addAll(SymbolBinder.bind(table, decls).errors());
        errors.addAll(TypeChecker.check(table, decls).errors());
        if (errors.isEmpty()) System.out.println("编译通过，无错误");
        else for (CompileError e : errors) System.out.println("  " + e);
        org.junit.jupiter.api.Assertions.assertTrue(errors.isEmpty(), "多输入脚本应能通过编译");
        System.out.println("三态判定: " + new dev.programmablefurnace.base.CompilerAnalyzer().analyze(src));
        org.junit.jupiter.api.Assertions.assertEquals(
                ContentState.EXECUTABLE, new dev.programmablefurnace.base.CompilerAnalyzer().analyze(src), "应为可执行程序");

        FakeFurnaceEnv env = new FakeFurnaceEnv();
        Executor ex = Executor.of(decls, table, env);
        final int TICKS = 900;   // 2 块粗铁（双输入槽各 1），每块烧 200 tick + 补料间隙
        for (int t = 0; t < TICKS; t++) {
            env.tick();
            ExecResult r = ex.tick();
            if (!r.ok()) { System.out.println("tick " + t + " 运行期错误: " + r); org.junit.jupiter.api.Assertions.fail("运行期错误: " + r); break; }
            if (t % 100 == 0 || t == TICKS - 1) {
                System.out.println("tick " + t + ": 进度=[" + env.progress[0] + "," + env.progress[1] + "] 燃烧=" + env.burnTime
                        + "  输入=[" + fmt(env.input[0]) + "," + fmt(env.input[1]) + "] 燃料=" + fmt(env.fuel[0])
                        + " 输出=[" + fmt(env.output[0]) + "," + fmt(env.output[1]) + "] 箱子=[" + fmt(env.chest[0]) + "," + fmt(env.chest[1]) + "," + fmt(env.chest[2]) + "]");
            }
        }
        System.out.println("=== 结束：点火 " + env.litCount + " 次，配方产出铁锭 " + env.producedCount + " 个 ===");
        int ingots = 0;
        for (ItemData d : env.chest) if (d != null && "铁锭".equals(d.name())) ingots += d.count();
        System.out.println("箱子现有: [" + fmt(env.chest[0]) + "," + fmt(env.chest[1]) + "," + fmt(env.chest[2]) + "] 铁锭合计 x" + ingots
                + (ingots >= 2 ? "  ← 多输入并行烧炼成功（双输入槽独立产出，堆叠合并回箱）" : ""));
        org.junit.jupiter.api.Assertions.assertTrue(ingots >= 2, "双输入槽并行烧炼应至少产出 2 个铁锭，实际 " + ingots);
    }


    private static String fmt(Object d) {
        if (!(d instanceof ItemData it) || it.isEmpty()) return "空";
        return it.name() + "x" + it.count();
    }
}
