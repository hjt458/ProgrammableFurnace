package dev.programmablefurnace.compiler.lex;

/**
 * 词法异常：扫描失败时抛出，携带编译错误（L 类错误码 + 定位）。
 * <p>Lexer 默认采用错误累积模式（{@link Lexer#errors()}），仅在需要立即中断时才抛出本异常。</p>
 */
public final class LexException extends RuntimeException {

    private final CompileError error;

    public LexException(CompileError error) {
        super(error.toString());
        this.error = error;
    }

    public CompileError error() { return error; }
}
