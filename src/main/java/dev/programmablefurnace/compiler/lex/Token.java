package dev.programmablefurnace.compiler.lex;

/**
 * 记号（Token）：词法分析的最小产出单元。
 * <p>含类型、文本、起始位置（文件名:行号:列号）与是否为关键字标志。</p>
 */
public final class Token {

    private final TokenType type;
    private final String text;
    private final SourcePos pos;
    private final boolean keyword;

    public Token(TokenType type, String text, SourcePos pos, boolean keyword) {
        this.type = type;
        this.text = text;
        this.pos = pos;
        this.keyword = keyword;
    }

    public TokenType type() { return type; }
    public String text() { return text; }
    public SourcePos pos() { return pos; }
    public boolean keyword() { return keyword; }

    /** 行号（供错误定位用）。 */
    public int line() { return pos.line(); }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + pos.location();
    }
}
