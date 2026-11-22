package dev.programmablefurnace.compiler;

/**
 * 代码编辑器纯文本模型：文本、光标、行号索引与垂直滚动。
 * <p>不依赖任何 MC 类，可在纯 JVM 测试（classpath 无 MC）中直接单测。
 * 由 {@link CodeEditorWidget} 委托，insert/delete/换行/移动/滚动等全部文本逻辑均在此。</p>
 */
public final class CodeEditorLogic {

    private String value = "";
    private int cursor = 0;
    private int scrollTop = 0;
    private int desiredCol = 0;
    private final int height;
    private final int lineHeight;

    public CodeEditorLogic(int height, int lineHeight) {
        this.height = height;
        this.lineHeight = lineHeight;
    }

    // ===================== 文本 =====================

    public String getValue() { return value; }

    public void setValue(String v) {
        value = v == null ? "" : v;
        if (cursor > value.length()) cursor = value.length();
        clampScroll();
    }

    // ===================== 光标 =====================

    public int getCursor() { return cursor; }

    public void setCursor(int c) { cursor = Math.max(0, Math.min(c, value.length())); }

    public int getDesiredCol() { return desiredCol; }

    public void setDesiredCol(int c) { desiredCol = c; }

    // ===================== 行号 =====================

    public int lineCount() {
        int n = 1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 第 line 行在 value 中的起始字符索引（越界返回 value.length()）。 */
    public int lineStart(int line) {
        int n = 0;
        int idx = 0;
        while (n < line) {
            int nl = value.indexOf('\n', idx);
            if (nl < 0) return value.length();
            idx = nl + 1;
            n++;
        }
        return Math.min(idx, value.length());
    }

    /** 光标所在行（0-based）。 */
    public int cursorLine() {
        int n = 0;
        for (int i = 0; i < cursor && i < value.length(); i++) {
            if (value.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 光标在当前行的列。 */
    public int cursorCol() {
        return cursor - lineStart(cursorLine());
    }

    /** 把 (line,col) 归一化回光标索引。 */
    private int cursorFor(int line, int col) {
        int start = lineStart(line);
        int end = lineStart(line + 1);
        int len = Math.max(0, end - start);
        if (line < lineCount() - 1 && len > 0 && value.charAt(end - 1) == '\n') {
            len--; // 行本身不含换行符
        }
        int c = Math.min(Math.max(0, col), len);
        return start + c;
    }

    // ===================== 滚动 =====================

    public int visibleLines() { return Math.max(1, height / lineHeight); }

    public int maxScrollTop() { return Math.max(0, lineCount() - visibleLines()); }

    public int getScrollTop() { return scrollTop; }

    public void setScrollTop(int s) { scrollTop = s; clampScroll(); }

    private void clampScroll() {
        int max = maxScrollTop();
        if (scrollTop > max) scrollTop = max;
        if (scrollTop < 0) scrollTop = 0;
    }

    public void scrollToCursor() {
        int cl = cursorLine();
        if (cl < scrollTop) {
            scrollTop = cl;
        } else if (cl >= scrollTop + visibleLines()) {
            scrollTop = cl - visibleLines() + 1;
        }
        clampScroll();
    }

    /** 第 line 行的文本（不含行尾换行符）。 */
    public String rowOf(int line) {
        int s = lineStart(line);
        int e = lineStart(line + 1);
        if (e > s && value.charAt(e - 1) == '\n') e--;
        return value.substring(s, Math.max(s, e));
    }

    // ===================== 编辑 =====================

    public void insert(String s) {
        String text = normalizeInserted(s);
        if (text.isEmpty()) return;
        value = value.substring(0, cursor) + text + value.substring(cursor);
        cursor += text.length();
        desiredCol = cursorCol();
        scrollToCursor();
    }

    /**
     * 把外部输入（粘贴板、按键映射）整理成编辑器能接受的文本。
     *
     * <p>为什么单独抽出来：从系统剪贴板或浏览器复制来的代码常带 {@code \r\n}，而本编辑器的行模型
     * 只认 {@code \n}——混进 {@code \r} 后行号会多算、光标位移也会错一格，且这种脏字符在界面上
     * 看不出来（渲染时被忽略）。同理，制表符统一成 4 空格，与按 Tab 键插入的内容保持一致，
     * 否则同一份代码里会出现两种缩进宽度。</p>
     */
    public static String normalizeInserted(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
    }

    public void deleteBefore() {
        if (cursor <= 0) return;
        value = value.substring(0, cursor - 1) + value.substring(cursor);
        cursor--;
        desiredCol = cursorCol();
        scrollToCursor();
    }

    public void deleteAfter() {
        if (cursor >= value.length()) return;
        value = value.substring(0, cursor) + value.substring(cursor + 1);
        desiredCol = cursorCol();
        scrollToCursor();
    }

    /** 光标在垂直方向移动一行，尽量保持 desiredCol。 */
    public void moveLine(int dir) {
        int line = cursorLine();
        int col = desiredCol;
        int newLine = line + dir;
        if (newLine < 0 || newLine >= lineCount()) return;
        cursor = cursorFor(newLine, col);
        scrollToCursor();
    }
}
