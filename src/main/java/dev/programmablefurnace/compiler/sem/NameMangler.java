package dev.programmablefurnace.compiler.sem;

import java.util.List;

/**
 * 名称修饰 / 转义（草稿 §2.2 ③、§2.6 ④）：把源码中的逻辑名（模块 + namespace 分区 + 名字）
 * 转义为能执行的实际函数名 / 变量名，作为运行期分发表（执行器）的唯一键。
 * <p>确定性、可读、防跨模块冲突：{@code PF/模块名[/namespace路径]/名字}；函数追加形参类型
 * 后缀以消解重载（草稿 §3.4 普通函数可重载）。执行入口 {@code RunLoop} 用固定键 {@code PF/ENTRY/模块名}。</p>
 */
public final class NameMangler {

    private NameMangler() {}

    /** 函数的实际可调用名，如 `PF/smelting/炉/熔炼(int,int)`。 */
    public static String mangleFunc(String module, String nsPath, String name, List<String> paramTypes) {
        StringBuilder sb = new StringBuilder("PF/").append(qualify(module, nsPath, name)).append('(');
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(paramTypes.get(i));
        }
        return sb.append(')').toString();
    }

    /** 变量的实际名字（运行期槽位键），如 `PF/smelting/炉/进度`。 */
    public static String mangleVar(String module, String nsPath, String name) {
        return "PF/" + qualify(module, nsPath, name);
    }

    /** 执行入口 RunLoop 的固定运行名（草稿 §3.4）：`PF/ENTRY/模块名`。 */
    public static String entryName(String module) {
        return "PF/ENTRY/" + safe(module);
    }

    private static String qualify(String module, String nsPath, String name) {
        StringBuilder sb = new StringBuilder(safe(module));
        if (nsPath != null && !nsPath.isEmpty() && !"<module>".equals(nsPath)) {
            for (String seg : nsPath.split("::")) sb.append('/').append(safe(seg));
        }
        return sb.append('/').append(safe(name)).toString();
    }

    /** 转义为安全键：保留字母/数字/下划线/中文，其余统一为下划线。 */
    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_\\u4e00-\\u9fa5]", "_");
    }
}
