package dev.programmablefurnace.base;

/**
 * 可切换双界面模式的方块实体能力接口（模块化）。
 * 任何实现方（编译器、执行器等）提供"工作界面/依赖库界面"两套槽位，通过 {@code applySwitchMode}
 * 切换当前生效的一套，供通用 {@code UiModePacket} 服务端同步复用。
 */
public interface UIModeSwitcher {

    /**
     * 切换界面模式。
     *
     * @param uiMode 0 = 第一界面（编译/工作），1 = 第二界面（依赖库）
     * @return 是否发生切换（模式确实改变）
     */
    boolean applySwitchMode(int uiMode);
}
