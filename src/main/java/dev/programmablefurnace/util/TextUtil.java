package dev.programmablefurnace.util;

import net.minecraft.client.gui.Font;

/**
 * 通用文本工具（静态方法，全局可用）。
 * <p>命名空间由包（{@code dev.programmablefurnace.util}）+ 类名限定，调用一律用 {@code TextUtil.xxx()} 静态访问，
 * 避免同名方法在不同类里重复出现/跨域混淆。</p>
 */
public final class TextUtil {

    private TextUtil() {}

    /** 取字符串第一行（不含换行符），兼容 CRLF（\\r\\n），无内容返回空串。 */
    public static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "";
        int nl = s.indexOf('\n');
        if (nl < 0) return s;
        // 兼容 Windows 换行 CRLF：行尾的 \r 一并去掉
        return (nl > 0 && s.charAt(nl - 1) == '\r') ? s.substring(0, nl - 1) : s.substring(0, nl);
    }

    /**
     * 按最大像素宽度截断文本，超出宽度时截断并追加省略号，不足则原样返回。
     *
     * @param maxPx 允许的最大像素宽度
     * @param font  用于测量宽度的字体
     */
    public static String clipWidth(String s, int maxPx, Font font) {
        if (s == null || s.isEmpty()) return "";
        if (font.width(s) <= maxPx) return s;
        int avail = Math.max(1, maxPx - font.width("…"));
        return font.plainSubstrByWidth(s, avail) + "…";
    }
}
