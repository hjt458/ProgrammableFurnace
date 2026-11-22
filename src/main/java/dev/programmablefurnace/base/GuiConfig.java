package dev.programmablefurnace.base;

import com.google.gson.Gson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GUI 通用布局配置：从打包进 jar 的 JSON（resources/{@code programmablefurnace/gui_config.json}）统一加载
 * 各机器共用的布局坐标/尺寸，避免在代码中硬编码。GUI 文字文本不在此管理——
 * 由 MC 标准语言文件（lang/zh_cn.json、en_us.json）通过 {@code Component.translatable} 本地化。
 * 各机器的<b>容器容量与输出权限</b>另由 {@link MachineConfigs}（compiler_config.json / furnace_config.json）管理。
 * 由 {@code ProgrammableFurnaceMod} 在注册阶段调用 {@link #load()} 加载一次，之后静态访问。
 * <p>纯 JVM 单测不依赖本类（Screen/Menu 运行于 MC 环境，此时已在注册时加载）。</p>
 * <p><b>为什么加载时就校验</b>：Gson 把缺失的 {@code int} 静默读成 0、把缺失的数组读成 null。
 * 0 会让按钮"存在但点不到"、null 会让界面在打开时 NPE——两者都发生在玩家点开 GUI 之后，
 * 堆栈里只会写 {@code Can't render screen}，没人能从中看出是配置键拼错了。所以宁可启动时
 * 一次性把问题列全抛出来（消息里带 JSON 路径），也不要留到运行期变成谜题。</p>
 */
public final class GuiConfig {

    private static final String FILE = "programmablefurnace/gui_config.json";

    /** 已知的槽位布局模式：无拓展卡（111）与三种 U 盘档位（212/313/414）。 */
    private static final List<String> MODE_KEYS = List.of("111", "212", "313", "414");

    private static Layout LAYOUT = null;
    private static Map<String, GuiMode> MODES = null;

    private GuiConfig() {}

    /** 注册阶段调用：从 classpath 读取 gui_config.json，校验后反序列化为静态布局配置。 */
    public static void load() {
        try (InputStream in = GuiConfig.class.getClassLoader().getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IllegalStateException("缺少 GUI 配置文件 resources/" + FILE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            GuiConfigData data = parse(json);
            LAYOUT = data.layout;
            MODES = data.modes;
        } catch (IllegalStateException e) {
            throw new IllegalStateException("加载 " + FILE + " 失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("加载 " + FILE + " 失败（读取或 JSON 解析异常）", e);
        }
    }

    /** 解析并校验配置文本；单独拆出来是为了让单测能直接喂坏配置，不必真的改资源文件。 */
    static GuiConfigData parse(String json) {
        GuiConfigData data = new Gson().fromJson(json, GuiConfigData.class);
        if (data == null) throw new IllegalStateException("文件内容为空，或不是 JSON 对象");
        List<String> problems = new ArrayList<>();
        validateLayout(data.layout, problems);
        validateModes(data.modes, problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("配置不完整（" + problems.size() + " 处）：\n  - "
                    + String.join("\n  - ", problems));
        }
        return data;
    }

    /** 布局配置（坐标/尺寸均为相对 GUI 左上角的像素值）。 */
    public static Layout layout() {
        return LAYOUT;
    }

    /**
     * 指定槽位布局模式（111/212/313/414）的槽位/坐标配置，与执行器 guiMode 对应。
     * 未知模式或未配置返回 {@code null}（调用方应回退到默认 111 布局）。
     */
    public static GuiMode mode(int code) {
        if (MODES == null) return null;
        return MODES.get(Integer.toString(code));
    }

    // ===================== 校验 =====================

    private static void validateLayout(Layout l, List<String> out) {
        if (l == null) {
            out.add("缺少 layout");
            return;
        }
        require(out, l.compile, "layout.compile");
        require(out, l.furnace, "layout.furnace");
        require(out, l.dependency, "layout.dependency");
        require(out, l.tab, "layout.tab");
        require(out, l.panel, "layout.panel");
        require(out, l.editor, "layout.editor");
        require(out, l.compileBtn, "layout.compileBtn");
        require(out, l.slots, "layout.slots");
        require(out, l.extra, "layout.extra");

        // 尺寸类字段必须为正：0 是"键名写错"的典型症状（Gson 静默补 0），而尺寸为 0 的控件等于不存在。
        if (l.panel != null) {
            positive(out, l.panel.w, "layout.panel.w");
            positive(out, l.panel.h, "layout.panel.h");
        }
        if (l.editor != null) {
            positive(out, l.editor.w, "layout.editor.w");
            positive(out, l.editor.h, "layout.editor.h");
        }
        if (l.compileBtn != null) {
            positive(out, l.compileBtn.w, "layout.compileBtn.w");
            positive(out, l.compileBtn.h, "layout.compileBtn.h");
        }
        if (l.compile != null) {
            positive(out, l.compile.startBtnW, "layout.compile.startBtnW");
            positive(out, l.compile.startBtnH, "layout.compile.startBtnH");
        }
        if (l.furnace != null) {
            positive(out, l.furnace.btnW, "layout.furnace.btnW");
            positive(out, l.furnace.btnH, "layout.furnace.btnH");
        }
        if (l.dependency != null) {
            positive(out, l.dependency.slotSize, "layout.dependency.slotSize");
            positive(out, l.dependency.listW, "layout.dependency.listW");
            positive(out, l.dependency.listH, "layout.dependency.listH");
            positive(out, l.dependency.rowH, "layout.dependency.rowH");
        }
        if (l.tab != null) {
            positive(out, l.tab.w, "layout.tab.w");
            positive(out, l.tab.h, "layout.tab.h");
        }
        if (l.extra != null) {
            pair(out, l.extra.x, l.extra.y, "layout.extra.x", "layout.extra.y");
        }
    }

    private static void validateModes(Map<String, GuiMode> modes, List<String> out) {
        if (modes == null) {
            out.add("缺少 modes");
            return;
        }
        for (String key : MODE_KEYS) {
            GuiMode m = modes.get(key);
            if (m == null) {
                out.add("缺少 modes." + key);
                continue;
            }
            validateMode(m, "modes." + key, out);
        }
    }

    private static void validateMode(GuiMode m, String p, List<String> out) {
        if (m.inputCount < 1) {
            out.add(p + ".inputCount 必须 >= 1（当前 " + m.inputCount + "）");
        }
        if (m.outputCount < 1) {
            out.add(p + ".outputCount 必须 >= 1（当前 " + m.outputCount + "）");
        }
        positive(out, m.panelW, p + ".panelW");

        pair(out, m.inputX, m.inputY, p + ".inputX", p + ".inputY");
        pair(out, m.fuelX, m.fuelY, p + ".fuelX", p + ".fuelY");
        pair(out, m.outputX, m.outputY, p + ".outputX", p + ".outputY");
        pair(out, m.arrowX, m.arrowY, p + ".arrowX", p + ".arrowY");

        // 数量字段与坐标数组必须自洽：否则会出现"声明 4 槽只画了 3 个框"这类肉眼难查的错位。
        countMatches(out, m.inputX, m.inputCount, p + ".inputX", p + ".inputCount");
        countMatches(out, m.outputX, m.outputCount, p + ".outputX", p + ".outputCount");
        if (m.arrowX != null && m.inputX != null && m.arrowX.length != m.inputX.length) {
            out.add(p + ".arrowX 数量（" + m.arrowX.length + "）应等于输入槽数（" + m.inputX.length + "）");
        }
    }

    private static void require(List<String> out, Object group, String path) {
        if (group == null) out.add("缺少 " + path);
    }

    private static void positive(List<String> out, int value, String path) {
        if (value <= 0) {
            out.add(path + " 必须是正数（当前 " + value + "；键名拼错会被 Gson 读成 0）");
        }
    }

    private static void pair(List<String> out, int[] xs, int[] ys, String px, String py) {
        if (xs == null && ys == null) {
            out.add("缺少 " + px + " 与 " + py);
            return;
        }
        if (xs == null || ys == null) {
            out.add(px + " 与 " + py + " 必须同时存在");
            return;
        }
        if (xs.length == 0) out.add(px + " 不能是空数组");
        if (xs.length != ys.length) {
            out.add(px + " 长度 " + xs.length + " 与 " + py + " 长度 " + ys.length + " 不一致");
        }
    }

    private static void countMatches(List<String> out, int[] coords, int count, String pCoords, String pCount) {
        if (coords != null && coords.length != count) {
            out.add(pCoords + " 长度 " + coords.length + " 与 " + pCount + "（" + count + "）不一致");
        }
    }

    // ===================== JSON 数据模型 =====================

    /** 顶层。 */
    /** 顶层；包内可见，好让单测直接构造/读取校验结果。 */
    static class GuiConfigData {
        Layout layout;
        Map<String, GuiMode> modes;
    }

    /** 布局分组。 */
    public static class Layout {
        public Compile compile;
        public Furnace furnace;
        public Dependency dependency;
        public Tab tab;
        public Panel panel;
        public Editor editor;
        public CompileBtn compileBtn;
        public Slots slots;
        /** 最右侧拓展槽坐标（放 U盘扩展卡等拓展物品；用户改背景图后对位）。 */
        public Extra extra;
    }

    /** 最右侧拓展槽坐标（各槽位左上角像素，与 menu 的 Slot.x/y 对应）。 */
    public static class Extra {
        public int[] x, y;
    }

    /** 编译模式布局。 */
    public static class Compile {
        public int startBtnX, startBtnY, startBtnW, startBtnH;
    }

    /** 执行器（可编程熔炉）工作界面"开始/终止"按钮布局。 */
    public static class Furnace {
        public int startX, startY, stopX, stopY, btnW, btnH;
    }

    /** 依赖库模式布局。 */
    public static class Dependency {
        public int inX, outX, slotY, slotSize;
        public int listX, listY, listW, listH, rowH;
    }

    /** 左侧页签栏。 */
    public static class Tab {
        public int x, y, w, h;
    }

    /** 二层代码编辑面板尺寸。 */
    public static class Panel {
        public int w, h;
    }

    /** 二层代码编辑器区域。 */
    public static class Editor {
        public int x, y, w, h;
    }

    /** 二层"编译"按钮尺寸。 */
    public static class CompileBtn {
        public int w, h;
    }

    /** 容器槽位坐标（menu 中的 Slot.x/y，物品 16x16 画在左上）。 */
    public static class Slots {
        public int compileInX, compileInY, compileOutX, compileOutY;
        public int depInX, depInY, depOutX, depOutY;
        public int hiddenX, hiddenY;
    }

    /** 槽位布局模式配置：某一 guiMode 下工作界面的槽位数量与背景图上的槽位/火焰/箭头坐标（相对 GUI 左上角像素）。 */
    public static class GuiMode {
        public int inputCount, outputCount;
        /** 背景图宽度（背景图按自身尺寸绘制，槽位坐标都相对它）。 */
        public int panelW;
        public int[] inputX, fuelX, outputX;
        /** 各槽位纵向坐标（数组与 inputX/fuelX/outputX 一一对应，支持纵向多行布局）。 */
        public int[] inputY, fuelY, outputY;
        public int flameX, flameY;
        /** 进度箭头坐标（每个输入→输出对一条箭头）。 */
        public int[] arrowX, arrowY;
    }
}
