package dev.programmablefurnace.compiler.exec;

/**
 * 不透明外部对象值（运行期）：容器、物品流、槽位、物品等由机器原语 {@link Env} 提供的对象。
 * <p>解释器不感知其内部结构，只把它当作可传递、可交给 {@link Env} 操作的句柄；对象间比较按
 * 委托对象相等性。这样运行器与游戏机器解耦（草稿 §3.8 执行边界）。</p>
 */
public record ExternalRef(Object delegate) {
    @Override
    public String toString() {
        return "external[" + delegate + "]";
    }
}
