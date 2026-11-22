package dev.programmablefurnace.compiler.lex;

/**
 * 源码位置：文件名 + 行号 + 列号（1-based）。
 * <p>用于编译错误定位（见 {@link dev.programmablefurnace.compiler.lex.ErrorCode}）。
 * 草稿 §4.7 要求错误码带「文件名 + 行号」，列号作为补充定位信息。</p>
 */
public final class SourcePos {

    private final String fileName;
    private final int line;
    private final int col;

    public SourcePos(String fileName, int line, int col) {
        this.fileName = fileName == null ? "<unknown>" : fileName;
        this.line = line;
        this.col = col;
    }

    public String fileName() { return fileName; }
    public int line() { return line; }
    public int col() { return col; }

    /** 按草稿格式输出：`文件名:行号`（列号作为可选后缀）。 */
    public String location() {
        return fileName + ":" + line;
    }

    @Override
    public String toString() {
        return fileName + ":" + line + ":" + col;
    }
}
