package dev.programmablefurnace.base;

/**
 * 依赖库批量输入规则的通用纯逻辑（不依赖任何 MC 环境，可全局复用、可 UT 覆盖）。
 * <p>规则边界：</p>
 * <ul>
 *   <li>执行器及其子类：一个执行器同时只执行一个执行函数，故依赖库列表<b>只能有一个可执行文件</b>，
 *       可执行文件唯一且排最上、依赖库文件排在下方；</li>
 *   <li>编译器：不执行执行函数，可放多个可执行文件，由编译器自己的 push-front 实现体现（不经过本规则）。</li>
 * </ul>
 */
public final class BatchInputRules {

    private BatchInputRules() {}

    /**
     * 计算某个输入文件应插入依赖库容器的偏移。
     *
     * @param input        输入文件的 ContentState
     * @param occupiedCount 容器当前已占用条目数
     * @param capacity     容器容量上限
     * @param hasExecutable 容器当前是否已有可执行文件
     * @return 插入的容器偏移；不合法返回 -1（类型不允许 / 容器满 / 可执行已存在）
     */
    public static int dependencyInsertIndex(ContentState input, int occupiedCount, int capacity, boolean hasExecutable) {
        if (input != ContentState.EXECUTABLE && input != ContentState.DEPENDENCY && input != ContentState.READONLY) return -1; // 只能可执行/依赖/只读可执行
        if (occupiedCount >= capacity) return -1; // 满
        boolean isExec = input.isRunnable(); // 只读可执行同可执行
        if (isExec && hasExecutable) return -1; // 可执行唯一（含只读可执行）
        return isExec ? 0 : occupiedCount; // 可执行最上，依赖追加尾
    }
}
