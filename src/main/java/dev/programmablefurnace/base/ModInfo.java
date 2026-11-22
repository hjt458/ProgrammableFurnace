package dev.programmablefurnace.base;

/**
 * 与游戏无关的模组标识，单独放一层是为了让下层代码能引用 modid 而不依赖入口类。
 *
 * <p>{@code ProgrammableFurnaceMod} 是组合根（最上层），协议层如果为了一个字符串去 import 它，
 * 分层图上就多一条"下层引用上层"的边——编译能过，但"协议层不依赖入口"这条约束就名存实亡。
 */
public final class ModInfo {

    /** 与 {@code gradle.properties} 的 {@code mod_id}、资源目录名保持一致。 */
    public static final String MODID = "programmablefurnace";

    private ModInfo() {
    }
}
