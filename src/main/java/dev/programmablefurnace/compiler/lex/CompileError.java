package dev.programmablefurnace.compiler.lex;

/**
 * 编译错误：错误码 + 定位（文件名 + 行号）+ 描述。
 * <p>输出格式（草稿 §4.7）：`错误码 描述 @ 文件名:行号`，如 `S02 括号不匹配 @ smelting:3`。</p>
 */
public final class CompileError {

    private final ErrorCode code;
    private final SourcePos pos;
    private final String message;

    public CompileError(ErrorCode code, SourcePos pos, String message) {
        this.code = code;
        this.pos = pos;
        this.message = message;
    }

    public ErrorCode code() { return code; }
    public SourcePos pos() { return pos; }
    public String message() { return message; }

    @Override
    public String toString() {
        return code.code() + " " + message + " @ " + pos.location();
    }
}
