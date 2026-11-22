package dev.programmablefurnace.compiler.exec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.sem.StorageBuilder;
import dev.programmablefurnace.compiler.syntax.Ast;
import dev.programmablefurnace.compiler.syntax.Parser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 运行器（AST 解释器，草稿 §3.8）测试：RunLoop 执行、存储表变量跨 tick 持久、控制流 / 算术、
 * 本地函数调用、机器原语委托（Env）、依赖库文件无可执行入口、运行期错误。
 */
class ExecutorTest {

    /** 伪机器原语：槽位表 + 外部函数 + 可迭代对象。 */
    static final class FakeEnv implements Env {
        final Map<String, Object> slots = new HashMap<>();
        final Map<String, Object> fns = new HashMap<>();
        final java.util.Set<String> externals = new java.util.HashSet<>();
        int containerSeq = 0;

        @Override
        public Object call(String name, Object target, List<Object> args) {
            if (name.equals("Container")) return "容器#" + (++containerSeq);
            return fns.get(name);
        }

        @Override
        public Object read(String name, Object target) {
            if (target == null) return "global:" + name;   // 内置库常量 / 外部全局
            return slots.get(key(target, name));
        }

        @Override
        public boolean hasExternal(String name) { return externals.contains(name); }

        @Override
        public void write(String name, Object target, Object value) {
            slots.put(key(target, name), value);
        }

        @Override
        public List<?> iterable(Object obj) {
            if (obj instanceof ExternalRef e) return (List<?>) e.delegate();
            return (List<?>) obj;
        }

        /** 伪世界时间：默认 0，测试可改。 */
        long now = 0L;

        @Override
        public long gameTime() { return now; }

        static String key(Object t, String m) { return t + "." + m; }
    }

    private static List<Ast.Decl> parse(String src) {
        Lexer lx = new Lexer("t.mc", src);
        List<Token> toks = lx.tokenize();
        return new Parser(toks).parseFile();
    }

    private static Executor exec(String src, Env env) {
        List<Ast.Decl> d = parse(src);
        return Executor.of(d, StorageBuilder.build(d), env);
    }

    private static ExecResult run(String src) { return exec(src, new FakeEnv()).tick(); }

    // ===================== 状态持久（§3.8） =====================

    @Test
    void moduleVar_persistsAcrossTicks() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 进度 = 0;\nvoid RunLoop(int tick) { 进度 = 进度 + 1; }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("进度")).isEqualTo(1L);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("进度")).isEqualTo(2L);
    }

    // ===================== 算术 / 控制流 =====================

    @Test
    void arithmetic_precedence() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 总 = 0;\nvoid RunLoop(int tick) { 总 = 3 + 4 * 2; }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("总")).isEqualTo(11L);
    }

    @Test
    void ifElse_andWhile_andSwitch() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 值 = 0;\nvoid RunLoop(int tick) {"
                + " if (1 < 2) { 值 = 10; } else { 值 = -10; }"
                + " while (值 > 0) { 值 = 值 - 1; }"
                + " switch (值) { case 0: 值 = 99; break; default: 值 = -1; }"
                + "}", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("值")).isEqualTo(99L);
    }

    // ===================== 本地函数调用 =====================

    @Test
    void userFunction_call_and_return() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 结果 = 0;\nint 加(int a, int b) { return a + b; }"
                + "\nvoid RunLoop(int tick) { 结果 = 加(2, 3); }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("结果")).isEqualTo(5L);
    }

    // ===================== 机器原语委托（§2.5 运行期判断） =====================

    @Test
    void slotReadWrite_viaEnv() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nContainer 熔炉 = Container();\nvoid RunLoop(int tick) {"
                + " 熔炉.input = 5; int v = 熔炉.input; 输出 = v + 1; }"
                + "\nint 输出 = 0;", env);
        assertThat(ex.tick().ok()).isTrue();
        // env 收到写入与读取：输入槽 = 5，读出 5，输出 = 6
        assertThat(ex.variable("输出")).isEqualTo(6L);
        assertThat(env.slots.get("容器#1.input")).isEqualTo(5L);
    }

    @Test
    void externalFunction_andRangeFor() {
        FakeEnv env = new FakeEnv();
        env.externals.add("self");
        env.fns.put("searchByTag", new ExternalRef(List.of(1L, 2L, 3L)));
        Executor ex = exec("MODULE(\"a\")\nint 总和 = 0;\nvoid RunLoop(int tick) {"
                + " auto 箱子们 = searchByTag(self);"
                + " for (int x : 箱子们) { 总和 = 总和 + x; }"
                + "}", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("总和")).isEqualTo(6L);
    }

    // ===================== 无入口 / 运行期错误 =====================

    @Test
    void noRunLoop_isNoEntry() {
        ExecResult r = run("MODULE(\"lib\")\nint 熔炼(int n) { return n + 1; }");
        assertThat(r.status()).isEqualTo(ExecResult.Status.NO_ENTRY);
    }

    @Test
    void undefinedVariable_isRuntimeError() {
        ExecResult r = run("MODULE(\"a\")\nvoid RunLoop(int tick) { int x = 不存在; }");
        assertThat(r.status()).isEqualTo(ExecResult.Status.RUNTIME_ERROR);
    }

    // ===================== 失控脚本止血（2026-11-16 批次 0） =====================

    @Test
    void whileTrue_isAbortedByTickBudget() {
        // 无预算时这条会永久占住服务端 tick 线程（不是慢，是卡死）
        ExecResult r = run("MODULE(\"a\")\nvoid RunLoop(int tick) { while (true) { } }");
        assertThat(r.status()).isEqualTo(ExecResult.Status.RUNTIME_ERROR);
        assertThat(r.message()).contains("预算");
    }

    @Test
    void infiniteRecursion_isAbortedByCallDepth() {
        ExecResult r = run("MODULE(\"a\")\nint f(int n) { return f(n + 1); }"
                + "\nvoid RunLoop(int tick) { int x = f(0); }");
        assertThat(r.status()).isEqualTo(ExecResult.Status.RUNTIME_ERROR);
        assertThat(r.message()).contains("嵌套过深");
    }

    @Test
    void budgetResetsEveryTick() {
        // 预算按 tick 重置：慢程序不能"攒"欠账，但也不能一次超限就永久停摆
        Executor ex = exec("MODULE(\"a\")\nint 次数 = 0;\nvoid RunLoop(int tick) {"
                + " for (int i = 0; i < 100; i = i + 1) { 次数 = 次数 + 1; } }", new FakeEnv());
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("次数")).isEqualTo(200L);
    }

    @Test
    void entryParams_areBoundToTypeDefaults() {
        // 入口不走 callUser，形参曾未绑定 → 读 tick 报"未声明变量"，与"声明过"自相矛盾。
        // 第一个 int 形参按约定是"当前游戏刻"，所以这里断言它等于 Env.gameTime()（FakeEnv 默认 0）。
        Executor ex = exec("MODULE(\"a\")\nint 读到的 = -1;\nvoid RunLoop(int tick) { 读到的 = tick; }",
                new FakeEnv());
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("读到的")).isEqualTo(0L);
    }

    @Test
    void entryFirstIntParam_receivesEnvGameTime() {
        // tick 必须来自世界时间，而不是类型默认值：否则 tick % 20 只在第 0 tick 成立一次，静默失效
        FakeEnv env = new FakeEnv();
        env.now = 1234L;
        Executor ex = exec("MODULE(\"a\")\nint 读到的 = -1;\nvoid RunLoop(int tick) { 读到的 = tick; }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("读到的")).isEqualTo(1234L);
    }

    @Test
    void entryGameTime_isRefreshedEveryTick() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 读到的 = -1;\nvoid RunLoop(int tick) { 读到的 = tick; }", env);
        env.now = 20L;
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("读到的")).isEqualTo(20L);
        env.now = 40L;
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("读到的")).isEqualTo(40L);
    }

    @Test
    void entrySecondParam_stillTakesTypeDefault() {
        // 只有第一个 int 形参携带时间；其余形参仍是类型默认值（脚本可自行加参数占位）
        FakeEnv env = new FakeEnv();
        env.now = 7L;
        Executor ex = exec("MODULE(\"a\")\nint 甲 = -1;\nint 乙 = -1;\n"
                + "void RunLoop(int tick, int 其它) { 甲 = tick; 乙 = 其它; }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("甲")).isEqualTo(7L);
        assertThat(ex.variable("乙")).isEqualTo(0L);
    }

    // ===================== 事件入口：RunLoop() 红石触发 =====================

    @Test
    void signalEntry_isNotTheTickEntry() {
        // 无参 RunLoop() 是事件入口：常驻轮询不该顺带跑它，否则红石逻辑会变成每 tick 一次
        Executor ex = exec("MODULE(\"a\")\nint 次数 = 0;\nvoid RunLoop() { 次数 = 次数 + 1; }", new FakeEnv());
        assertThat(ex.tick().status()).isEqualTo(ExecResult.Status.NO_ENTRY);
        assertThat(ex.variable("次数")).isEqualTo(0L);
    }

    @Test
    void signalEntry_runsOnceWhenTriggered() {
        Executor ex = exec("MODULE(\"a\")\nint 次数 = 0;\nvoid RunLoop() { 次数 = 次数 + 1; }", new FakeEnv());
        assertThat(ex.trigger().ok()).isTrue();
        assertThat(ex.trigger().ok()).isTrue();
        assertThat(ex.variable("次数")).isEqualTo(2L);
    }

    @Test
    void tickEntry_andSignalEntry_coexist() {
        // 同一文件允许两个入口：常驻的读 tick，事件的只在触发时跑
        FakeEnv env = new FakeEnv();
        env.now = 40L;
        Executor ex = exec("MODULE(\"a\")\nint 时钟 = -1;\nint 次数 = 0;\n"
                + "void RunLoop(int tick) { 时钟 = tick; }\n"
                + "void RunLoop() { 次数 = 次数 + 1; }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("时钟")).isEqualTo(40L);
        assertThat(ex.variable("次数")).isEqualTo(0L);
        assertThat(ex.trigger().ok()).isTrue();
        assertThat(ex.variable("次数")).isEqualTo(1L);
    }

    @Test
    void noSignalEntry_triggerReturnsNoEntry() {
        Executor ex = exec("MODULE(\"a\")\nvoid RunLoop(int tick) { }", new FakeEnv());
        assertThat(ex.trigger().status()).isEqualTo(ExecResult.Status.NO_ENTRY);
    }

    @Test
    void signalEntry_errorIsReportedNotThrown() {
        // 事件入口的错误同样收敛成 ExecResult，不能让异常冒到方块 tick
        Executor ex = exec("MODULE(\"a\")\nvoid RunLoop() { 甲 = 1; }", new FakeEnv());
        ExecResult r = ex.trigger();
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("甲");
    }

    // ===================== 复合值：map / pair / tuple =====================

    @Test
    void map_declaredWithoutInit_isUsable_andCountsEntries() {
        // 声明即给空 map：`map<K,V> m; m[k] = v;` 是最常见写法，不该撞 null
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 值 = 0;\nvoid RunLoop(int tick) {"
                + " map<string,int> m; m[\"甲\"] = 7; m[\"乙\"] = m[\"甲\"] + 1;"
                + " 值 = m[\"乙\"] + m.size(); }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("值")).isEqualTo(10L);
    }

    @Test
    void map_hasAndRemove_andGetFallsBackToNull() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 值 = 0;\nvoid RunLoop(int tick) {"
                + " map<string,int> m; m.put(\"a\", 1);"
                + " if (m.has(\"a\")) { 值 = 值 + 1; }"        // 有 → +1
                + " if (m.has(\"b\")) { 值 = 值 + 100; }"      // 没有 → 不加
                + " m.remove(\"a\");"
                + " if (m.has(\"a\")) { 值 = 值 + 1000; }"    // 已删 → 不加
                + " }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("值")).isEqualTo(1L);
    }

    @Test
    void map_missingKey_isRuntimeErrorWithHint() {
        ExecResult r = run("MODULE(\"a\")\nvoid RunLoop(int tick) { map<string,int> m; int v = m[\"没有\"]; }");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("没有键").contains("has(");
    }

    @Test
    void pair_fieldsAreReadableAndWritable() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 值 = 0;\nstring 文本 = \"\";\n"
                + "void RunLoop(int tick) { pair<int,string> p = pair(3, \"甲\");"
                + " p.second = \"乙\"; 值 = p.first; 文本 = p.second; }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("值")).isEqualTo(3L);
        assertThat(ex.variable("文本")).isEqualTo("乙");
    }

    @Test
    void tuple_indexingAndSize() {
        FakeEnv env = new FakeEnv();
        Executor ex = exec("MODULE(\"a\")\nint 值 = 0;\nvoid RunLoop(int tick) {"
                + " tuple<int,int,int> t = tuple(1, 2, 3); t[1] = 9; 值 = t[1] + t.size(); }", env);
        assertThat(ex.tick().ok()).isTrue();
        assertThat(ex.variable("值")).isEqualTo(12L);
    }

    @Test
    void tuple_outOfRange_isRuntimeError() {
        ExecResult r = run("MODULE(\"a\")\nvoid RunLoop(int tick) { tuple<int> t = tuple(1); int v = t[3]; }");
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("下标越界");
    }
}
