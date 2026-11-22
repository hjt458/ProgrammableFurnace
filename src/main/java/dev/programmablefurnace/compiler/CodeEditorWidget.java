package dev.programmablefurnace.compiler;

import dev.programmablefurnace.util.TextUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * 轻量自绘代码编辑器（编译器二层 GUI 的输入框）：
 * <ul>
 *   <li>文本从顶部开始（不垂直居中），支持 Enter 换行；</li>
 *   <li>内容超高时可垂直滚动，无内容不滚动；</li>
 *   <li>右侧在可滚动时显示滚动条；</li>
 *   <li>最左侧显示行号，随滚动同步。</li>
 * </ul>
 * 支持光标移动（方向/Home/End）、Backspace/Delete、字符输入、Ctrl+V 粘贴、滚轮滚动、点击定位光标。
 * <p>文本/光标/滚动等纯逻辑委托给 {@link CodeEditorLogic}（可单测）；本类只负责渲染与 MC 事件转发。</p>
 */
public class CodeEditorWidget {

    private static final int COLOR_BG = 0xFF1E1E1E;
    private static final int COLOR_GUTTER = 0xFF171717;
    private static final int COLOR_GUTTER_LINE = 0xFF3A3A3A;
    private static final int COLOR_LINE_NO = 0xFF8A8A8A;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_CURSOR = 0xFFFFFFFF;
    private static final int COLOR_TRACK = 0xFF2A2A2A;
    private static final int COLOR_THUMB = 0xFF6E6E6E;
    private static final int SCROLLBAR_W = 6;

    private final Font font;
    private int x;
    private int y;
    private final int width;
    private final int height;
    private final int lineHeight;
    private final CodeEditorLogic logic;

    private boolean focused = false;
    /** keyPressed 已直接插入字符时置位，供 charTyped 防重（当前环境 overlay 非空时 charTyped 不会触发）。 */
    private boolean keyConsumedChar = false;

    public CodeEditorWidget(Font font, int x, int y, int width, int height) {
        this.font = font;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.lineHeight = font.lineHeight + 2;
        this.logic = new CodeEditorLogic(height, this.lineHeight);
    }

    // ===================== 文本模型（委托 logic） =====================

    public String getValue() { return logic.getValue(); }

    public void setValue(String v) { logic.setValue(v); }

    // ===================== 渲染 =====================

    private int gutterWidth() {
        int maxNo = Math.max(1, logic.lineCount());
        int w = font.width(String.valueOf(maxNo)) + 10;
        return Math.max(w, 22);
    }

    private int textX() { return x + gutterWidth() + 2; }

    private int textWidth() {
        return Math.max(1, width - gutterWidth() - 2 - (logic.maxScrollTop() > 0 ? SCROLLBAR_W : 0));
    }

    public void render(GuiGraphicsExtractor graphics) {
        // 背景
        graphics.fill(x, y, x + width, y + height, COLOR_BG);
        // 行号栏
        int gw = gutterWidth();
        graphics.fill(x, y, x + gw, y + height, COLOR_GUTTER);
        graphics.fill(x + gw, y, x + gw + 1, y + height, COLOR_GUTTER_LINE);

        int startLine = logic.getScrollTop();
        int visible = logic.visibleLines();
        int tx = textX();
        int tw = textWidth();
        int baseY = y + 3; // 顶部留 3px，行号与文本对齐

        for (int i = 0; i < visible; i++) {
            int line = startLine + i;
            if (line >= logic.lineCount()) break;
            int rowY = baseY + i * lineHeight;
            // 行号（右对齐在行号栏内）
            String no = String.valueOf(line + 1);
            int noX = x + gw - 4 - font.width(no);
            graphics.text(font, Component.literal(no), noX, rowY, COLOR_LINE_NO);
            // 文本行（裁剪到宽度，统一走 TextUtil）
            String row = TextUtil.clipWidth(logic.rowOf(line), tw, font);
            graphics.text(font, Component.literal(row), tx, rowY, COLOR_TEXT);
        }

        // 光标
        if (focused && (logic.getValue().isEmpty() || isInsertionPointVisible())) {
            int cl = logic.cursorLine();
            int rel = cl - logic.getScrollTop();
            if (rel >= 0 && rel < visible) {
                int cgX = tx + font.width(logic.getValue().substring(logic.lineStart(cl), logic.getCursor()));
                int cgY = baseY + rel * lineHeight;
                graphics.fill(cgX, cgY + 1, cgX + 1, cgY + font.lineHeight - 1, COLOR_CURSOR);
            }
        }

        // 右侧滚动条（仅在可滚动时显示）
        int max = logic.maxScrollTop();
        if (max > 0) {
            int sx = x + width - SCROLLBAR_W;
            graphics.fill(sx, y, sx + SCROLLBAR_W, y + height, COLOR_TRACK);
            int trackH = height;
            int thumbH = Math.max(12, (int) ((double) logic.visibleLines() / logic.lineCount() * trackH));
            int thumbY = y + (int) ((double) logic.getScrollTop() / max * (trackH - thumbH));
            graphics.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, COLOR_THUMB);
        }
    }

    private boolean isInsertionPointVisible() {
        int cl = logic.cursorLine();
        return cl >= logic.getScrollTop() && cl < logic.getScrollTop() + logic.visibleLines();
    }

    // ===================== 输入事件 =====================

    public boolean keyPressed(KeyEvent event) {
        if (!focused) return false;
        if (event.isPaste()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip != null && !clip.isEmpty()) logic.insert(clip);
            return true;
        }
        if (event.isSelectAll()) {
            logic.setCursor(logic.getValue().length());
            return true;
        }
        if (event.isCopy()) {
            // 无选区模型时复制当前行；去掉行尾换行，粘贴回去不会多出一行
            String row = logic.rowOf(logic.cursorLine());
            if (row.endsWith("\n")) row = row.substring(0, row.length() - 1);
            Minecraft.getInstance().keyboardHandler.setClipboard(row);
            return true;
        }
        if (event.isConfirmation()) {
            logic.insert("\n");
            return true;
        }
        if (event.isLeft()) {
            if (logic.getCursor() > 0) logic.setCursor(logic.getCursor() - 1);
            logic.setDesiredCol(logic.cursorCol());
            return true;
        }
        if (event.isRight()) {
            if (logic.getCursor() < logic.getValue().length()) logic.setCursor(logic.getCursor() + 1);
            logic.setDesiredCol(logic.cursorCol());
            return true;
        }
        if (event.isUp()) {
            logic.moveLine(-1);
            return true;
        }
        if (event.isDown()) {
            logic.moveLine(1);
            return true;
        }

        int key = event.key();
        switch (key) {
            case InputConstants.KEY_BACKSPACE:
                logic.deleteBefore();
                return true;
            case InputConstants.KEY_DELETE:
                logic.deleteAfter();
                return true;
            case InputConstants.KEY_HOME:
                logic.setCursor(logic.lineStart(logic.cursorLine()));
                logic.setDesiredCol(logic.cursorCol());
                return true;
            case InputConstants.KEY_END: {
                int c = logic.lineStart(logic.cursorLine() + 1);
                if (c > 0 && logic.getValue().charAt(c - 1) == '\n') c--;
                logic.setCursor(c);
                logic.setDesiredCol(logic.cursorCol());
                return true;
            }
            default: {
                // MC 26.3 的 charTyped 依赖 gui.overlay()==null；当前环境 overlay 非空导致字符事件不分发。
                // 此处直接在 keyPressed 里把字母/数字/符号键码映射成字符插入，绕开 charTyped。
                String ch = keyToChar(event);
                if (ch != null) {
                    logic.insert(ch);
                    keyConsumedChar = true;
                    return true;
                }
                return false;
            }
        }
    }

    public boolean charTyped(CharacterEvent event) {
        // keyPressed 已处理字符（overlay 非空环境 charTyped 本不触发；overlay 为空时防重复插入）。
        if (keyConsumedChar) {
            keyConsumedChar = false;
            return true;
        }
        if (!focused) return false;
        if (!event.isAllowedChatCharacter()) return false;
        String s = event.codepointAsString();
        logic.insert(s);
        return true;
    }

    public boolean mouseClicked(MouseButtonEvent event) {
        double mx = event.x(), my = event.y();
        if (!isMouseOver(mx, my)) return false;
        focused = true;
        int rel = (int) ((my - (y + 3)) / lineHeight);
        int line = logic.getScrollTop() + rel;
        if (line < 0 || line >= logic.lineCount()) {
            logic.setCursor(logic.getValue().length());
        } else {
            // 由点击 x 反推列
            int start = logic.lineStart(line);
            int end = logic.lineStart(line + 1);
            if (end > start && logic.getValue().charAt(end - 1) == '\n') end--;
            String row = logic.getValue().substring(start, Math.max(start, end));
            int tx = textX();
            int col = 0;
            for (int i = 0; i <= row.length(); i++) {
                if (tx + font.width(row.substring(0, i)) > mx) {
                    col = i - 1;
                    break;
                }
            }
            logic.setCursor(start + Math.max(0, Math.min(col, Math.max(0, end - start))));
        }
        logic.setDesiredCol(logic.cursorCol());
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double vertical) {
        if (!isMouseOver(mx, my)) return false;
        int max = logic.maxScrollTop();
        if (max <= 0) return true; // 无可滚动内容，不滚动
        logic.setScrollTop(Math.max(0, Math.min(max, logic.getScrollTop() - (int) Math.signum(vertical) * 2)));
        return true;
    }

    public boolean isFocused() { return focused; }

    /** 键码 → 可输入字符（仅字母/数字/常用符号/空格/Tab；方向、回车、Backspace 等特殊键返回 null 由别处处理）。 */
    private String keyToChar(KeyEvent event) {
        // 带 Ctrl/Alt 的组合键是快捷键（Ctrl+C 复制、Ctrl+V 粘贴、Alt+Tab……），不能当普通字符插入，
        // 否则按 Ctrl+C 会在正文里多个 "c"。Shift 例外：它正是用于输入大写与上档符号。
        if (event.hasControlDown() || event.hasAltDown()) return null;
        int key = event.key();
        boolean shift = event.hasShiftDown();
        // 字母 A-Z（InputConstants：A=4 连续到 Z=29）
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
            char c = (char) ('a' + (key - InputConstants.KEY_A));
            return shift ? String.valueOf(Character.toUpperCase(c)) : String.valueOf(c);
        }
        // 数字 1-9（30-38）
        if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
            char c = (char) ('1' + (key - InputConstants.KEY_1));
            if (shift) return String.valueOf("!@#$%^&*(".charAt(c - '1'));
            return String.valueOf(c);
        }
        switch (key) {
            case InputConstants.KEY_0: return shift ? ")" : "0";
            case InputConstants.KEY_MINUS: return shift ? "_" : "-";
            case InputConstants.KEY_EQUALS: return shift ? "+" : "=";
            case InputConstants.KEY_LBRACKET: return shift ? "{" : "[";
            case InputConstants.KEY_RBRACKET: return shift ? "}" : "]";
            case InputConstants.KEY_BACKSLASH: return shift ? "|" : "\\";
            case InputConstants.KEY_SEMICOLON: return shift ? ":" : ";";
            case InputConstants.KEY_APOSTROPHE: return shift ? "\"" : "'";
            case InputConstants.KEY_GRAVE: return shift ? "~" : "`";
            case InputConstants.KEY_COMMA: return shift ? "<" : ",";
            case InputConstants.KEY_PERIOD: return shift ? ">" : ".";
            case InputConstants.KEY_SLASH: return shift ? "?" : "/";
            case InputConstants.KEY_SPACE: return " ";
            case InputConstants.KEY_TAB: return "    ";
            default: return null;
        }
    }
    public void setFocused(boolean f) { this.focused = f; }

    /** 移动编辑器：窗口尺寸变化时 {@code init()} 会重算居中位置，文本与光标必须保留（只挪位置，不重建）。 */
    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }
}
