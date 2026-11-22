package dev.programmablefurnace.compiler.lex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 词法分析器（Lexer）：把源代码字符串转成 {@link Token} 流（草稿 §2 编译期第①步 + §3 词法要素）。
 * <p>纯 JVM 实现，无 MC 依赖，可在 classpath 无 MC 时直接单测。扫描失败时抛出 {@link LexException}，
 * 携带错误码（L 类）与定位（文件名 + 行号 + 列号）。</p>
 */
public final class Lexer {

    /** 关键字集（草稿 §3 已确认）：控制流 + 模块 + 类型 + 字面量。 */
    private static final Set<String> KEYWORDS = new HashSet<>(List.of(
            // 控制流
            "if", "else", "switch", "case", "default", "for", "while", "do", "return", "break", "continue",
            // 模块 / 导入 / 命名空间
            "MODULE", "import", "from", "using", "namespace",
            // 基础类型
            "int", "float", "double", "bool", "char", "void", "string",
            // 进阶类型
            "vector", "map", "pair", "tuple", "auto", "ItemStream", "Container",
            // 物品/物品类型（草稿 F2）
            "Item", "ItemType",
            // 字面量
            "true", "false",
            // 执行入口
            "RunLoop"));

    private final String fileName;
    private final String src;
    private int idx = 0;
    private int line = 1;
    private int col = 1;
    private final List<CompileError> errors = new ArrayList<>();

    public Lexer(String fileName, String src) {
        this.fileName = fileName;
        this.src = src == null ? "" : src;
    }

    /** 扫描全部 Token（含 EOF）。错误累积在 {@link #errors()}，不中断扫描。 */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }
            if (c == '/' && peek(1) == '/') { skipLineComment(); continue; }
            if (c == '/' && peek(1) == '*') { skipBlockComment(); continue; }

            Token t = scanToken();
            if (t != null) tokens.add(t);
        }
        tokens.add(new Token(TokenType.EOF, "", pos(), false));
        return tokens;
    }

    private Token scanToken() {
        char c = peek();
        SourcePos p = pos();

        if (Character.isLetter(c) || c == '_') return scanIdentifier();
        if (Character.isDigit(c)) return scanNumber();
        if (c == '"') return scanString();

        switch (c) {
            case '+': advance(); return op(TokenType.PLUS, "+", p);
            case '-': advance(); return op(TokenType.MINUS, "-", p);
            case '*': advance(); return op(TokenType.STAR, "*", p);
            case '/': advance(); return op(TokenType.SLASH, "/", p);
            case '%': advance(); return op(TokenType.PERCENT, "%", p);
            case '=':
                if (peek(1) == '=') { advance(); advance(); return op(TokenType.EQ, "==", p); }
                advance(); return op(TokenType.ASSIGN, "=", p);
            case '!':
                if (peek(1) == '=') { advance(); advance(); return op(TokenType.NE, "!=", p); }
                advance(); return op(TokenType.NOT, "!", p);
            case '<':
                if (peek(1) == '=') { advance(); advance(); return op(TokenType.LE, "<=", p); }
                advance(); return op(TokenType.LT, "<", p);
            case '>':
                if (peek(1) == '=') { advance(); advance(); return op(TokenType.GE, ">=", p); }
                advance(); return op(TokenType.GT, ">", p);
            case '&':
                if (peek(1) == '&') { advance(); advance(); return op(TokenType.AND, "&&", p); }
                advance(); return op(TokenType.AMP, "&", p);
            case '|':
                if (peek(1) == '|') { advance(); advance(); return op(TokenType.OR, "||", p); }
                break;
            case '(': advance(); return op(TokenType.LPAREN, "(", p);
            case ')': advance(); return op(TokenType.RPAREN, ")", p);
            case '{': advance(); return op(TokenType.LBRACE, "{", p);
            case '}': advance(); return op(TokenType.RBRACE, "}", p);
            case '[': advance(); return op(TokenType.LBRACKET, "[", p);
            case ']': advance(); return op(TokenType.RBRACKET, "]", p);
            case ';': advance(); return op(TokenType.SEMICOLON, ";", p);
            case ':':
                if (peek(1) == ':') { advance(); advance(); return op(TokenType.COLON2, "::", p); }
                advance(); return op(TokenType.COLON, ":", p);
            case ',': advance(); return op(TokenType.COMMA, ",", p);
            case '.': advance(); return op(TokenType.DOT, ".", p);
            default:
                break;
        }

        // 认不出的字符分两类：本该是符号却不在语言里（L05），与根本不该出现在源码里的字符（L01）。
        // 分开报是因为作者能对前者改写法、对后者多半是编码或粘贴问题。
        if (isPrintableSymbol(c)) {
            error(ErrorCode.L05_UNKNOWN_SYMBOL, p, "未知符号 '" + c + "'");
        } else {
            error(ErrorCode.L01_ILLEGAL_CHAR, p, "非法字符 '" + c + "'（码位 U+"
                    + String.format("%04X", (int) c) + "）");
        }
        advance();
        return null;
    }

    /** 可打印的 ASCII 符号（不含已处理的运算符与空格）：这类字符报"未知符号"更有指导性。 */
    private static boolean isPrintableSymbol(char c) {
        return c >= '!' && c <= '~';
    }

    private Token scanIdentifier() {
        SourcePos p = pos();
        int start = idx;
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) advance();
        String text = src.substring(start, idx);
        if (KEYWORDS.contains(text)) return new Token(TokenType.KEYWORD, text, p, true);
        return new Token(TokenType.IDENTIFIER, text, p, false);
    }

    private Token scanNumber() {
        SourcePos p = pos();
        int start = idx;
        boolean isFloat = false;
        while (!atEnd() && Character.isDigit(peek())) advance();
        if (!atEnd() && peek() == '.' && Character.isDigit(peek(1))) {
            isFloat = true;
            advance(); // .
            while (!atEnd() && Character.isDigit(peek())) advance();
        }
        // 数字后面紧跟字母（如 12abc）或出现第二个小数点，几乎都是写错。
        // 不在这里报，token 会被劈成"数字 + 标识符"，最终错误落到别处，作者看不出真正原因。
        if (!atEnd() && (Character.isLetter(peek()) || peek() == '_')) {
            error(ErrorCode.L04_BAD_NUMBER, p, "数字后不能紧跟字母或下划线");
            while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) advance();
        } else if (isFloat && !atEnd() && peek() == '.' && Character.isDigit(peek(1))) {
            error(ErrorCode.L04_BAD_NUMBER, p, "数字里出现了第二个小数点");
            advance();
            while (!atEnd() && Character.isDigit(peek())) advance();
        }
        String text = src.substring(start, idx);
        if (isFloat) return new Token(TokenType.FLOAT, text, p, false);
        return new Token(TokenType.INTEGER, text, p, false);
    }

    private Token scanString() {
        SourcePos p = pos();
        advance(); // 开引号
        StringBuilder sb = new StringBuilder();
        while (!atEnd()) {
            char c = peek();
            if (c == '"') {
                advance(); // 闭引号
                return new Token(TokenType.STRING, sb.toString(), p, false);
            }
            if (c == '\n') break; // 未闭合且跨行 → 终止
            sb.append(c);
            advance();
        }
        error(ErrorCode.L02_UNTERMINATED_STRING, p, "字符串未闭合");
        // 跨行场景：不吞换行，交由外层 whitespace 处理
        return new Token(TokenType.STRING, sb.toString(), p, false);
    }

    private void skipLineComment() {
        while (!atEnd() && peek() != '\n') advance();
    }

    private void skipBlockComment() {
        SourcePos p = pos();
        advance(); advance(); // /*
        while (!atEnd() && !(peek() == '*' && peek(1) == '/')) advance();
        if (atEnd()) {
            error(ErrorCode.L06_UNTERMINATED_COMMENT, p, "块注释未闭合");
            return;
        }
        advance(); advance(); // */
    }

    private Token op(TokenType type, String text, SourcePos p) {
        return new Token(type, text, p, false);
    }

    private void error(ErrorCode code, SourcePos p, String msg) {
        errors.add(new CompileError(code, p, msg));
    }

    private boolean atEnd() { return idx >= src.length(); }
    private char peek() { return atEnd() ? '\0' : src.charAt(idx); }
    private char peek(int off) {
        int i = idx + off;
        return (i >= src.length()) ? '\0' : src.charAt(i);
    }
    private void advance() {
        if (atEnd()) return;
        if (src.charAt(idx) == '\n') { line++; col = 1; }
        else col++;
        idx++;
    }

    private SourcePos pos() { return new SourcePos(fileName, line, col); }

    /** 收集到的编译错误（词法阶段 L 类）。 */
    public List<CompileError> errors() { return errors; }
}
