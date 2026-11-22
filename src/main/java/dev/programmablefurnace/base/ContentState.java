package dev.programmablefurnace.base;

/**
 * 可编译文件的内容状态（与 {@link FileType} 正交：FileType 决定"依赖库/运行库、能否输出"，
 * 本枚举决定"空白/可执行/依赖/编译失败"及其图标与显示名）。与 {@link FileType} 命名对齐：
 * 运行库 ↔ 可执行文件(EXECUTABLE)、依赖库 ↔ 依赖文件(DEPENDENCY)。
 * <ul>
 *   <li>{@link #BLANK} 空白：无代码内容，图标白色 SIM，名"空白文件"</li>
 *   <li>{@link #EXECUTABLE} 可执行：代码非空且首行非空，图标蓝色 SIM，名"可执行文件"</li>
 *   <li>{@link #FAILED} 编译失败：代码非空但首行为空白（无法取文件名），图标红色 SIM，名"编译失败文件"</li>
 *   <li>{@link #DEPENDENCY} 依赖文件：成功编译产物，但缺少执行函数（执行函数名后续定，先占位），供依赖库使用</li>
 * </ul>
 */
public enum ContentState {
    BLANK,
    EXECUTABLE,
    FAILED,
    DEPENDENCY,
    /** 只读可执行：可放熔炉程序槽运行，但编译器拒绝重新编译（只读/锁定，后续经合成配方与可执行文件互转）。 */
    READONLY;

    /**
     * 这个状态的内容能不能被机器拿去执行（熔炉依赖库里的模块、程序入口）。
     * <p>"只读"只影响能不能被<b>重新编译</b>，不影响能不能<b>运行</b>——示例程序就是只读可执行，放进去照跑。
     * 这是"可运行"的<b>唯一</b>定义：以前熔炉 BE 里的 {@code isRunnableCode}、{@code base/BatchInputRules}
     * 里的 {@code isExec} 各自写过一遍，三处口径一旦分叉就会出现"能放进依赖库但不执行"或反之。</p>
     * <p>纯逻辑、零 MC 依赖，所以能进单测。</p>
     */
    public boolean isRunnable() {
        return this == EXECUTABLE || this == READONLY;
    }
}
