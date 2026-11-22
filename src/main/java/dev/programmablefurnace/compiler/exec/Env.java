package dev.programmablefurnace.compiler.exec;

import java.util.List;

/**
 * 机器原语环境（运行器桥接，草稿 §3.8 执行边界）。
 * <p>解释器 {@link Executor} 只负责程序逻辑（算术 / 控制流 / 状态）；所有与游戏机器相关的原语
 * ——槽位读写、物品/容器/物品流访问、遍历、外部（内置库/导入模块）函数——都委托本接口由游戏端实现。
 * 槽位"能否进槽"等运行期判断（§2.5）即落在这里。</p>
 */
public interface Env {

    /**
     * 调用外部函数 / 对象方法。
     * @param name    函数名或方法名（外部函数、内置库函数、限定名；对象方法时传成员名）
     * @param target  对象方法调用的接收者对象（外部函数调用时为 {@code null}）
     * @param args    实参（已求值的运行时值）
     */
    Object call(String name, Object target, List<Object> args);

    /**
     * 读取对象成员（对象.槽位 / 对象.属性）。
     * @param name   成员名
     * @param target 对象（外部全局读取时为 {@code null}）
     */
    Object read(String name, Object target);

    /**
     * 判断是否存在外部全局 / 内置常量（如 {@code 方块自身}）。
     * <p>解释器对"未声明的裸名"仅在 {@code hasExternal} 为真时才回退到 {@link #read}，
     * 否则判为未声明变量（运行期错误）。</p>
     */
    boolean hasExternal(String name);

    /**
     * 写入对象成员（对象.槽位 = 值）。
     * @param name   成员名
     * @param target 对象（外部全局写入时为 {@code null}）
     * @param value  要写入的值
     */
    void write(String name, Object target, Object value);

    /** 遍历对象（范围 for / 迭代）。 */
    List<?> iterable(Object obj);

    /**
     * 当前游戏刻（世界时间）。执行器在每个 tick 开头取一次，填进入口 {@code RunLoop(int tick)}
     * 的第一个 int 形参——约定就是这个形参携带当前刻，脚本据此做"每 20 tick 一次"这类调度。
     * <p>纯逻辑层不认识时钟，所以时间只能从这里来：{@code FurnaceEnv} 返回 {@code level.getGameTime()}。</p>
     */
    long gameTime();
}
