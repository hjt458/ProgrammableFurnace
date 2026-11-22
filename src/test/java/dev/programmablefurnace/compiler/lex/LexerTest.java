package dev.programmablefurnace.compiler.lex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link Lexer} 词法分析器单元测试。
 * 覆盖：关键字/标识符、数字/浮点、字符串、运算符、分隔符、注释跳过、错误码定位。
 */
class LexerTest {

    private List<Token> lex(String src) { return new Lexer("test.mc", src).tokenize(); }

    private List<CompileError> errors(String src) {
        Lexer l = new Lexer("test.mc", src);
        l.tokenize();
        return l.errors();
    }

    // ===================== 关键字 / 标识符 =====================

    @Test
    void keywordRecognized() {
        List<Token> ts = lex("int x");
        assertThat(ts.get(0).type()).isEqualTo(TokenType.KEYWORD);
        assertThat(ts.get(0).text()).isEqualTo("int");
        assertThat(ts.get(0).keyword()).isTrue();
    }

    @Test
    void identifierRecognized() {
        List<Token> ts = lex("粗铁流");
        assertThat(ts.get(0).type()).isEqualTo(TokenType.IDENTIFIER);
        assertThat(ts.get(0).keyword()).isFalse();
    }

    @Test
    void keywordSetCoversCore() {
        List<Token> ts = lex("MODULE import from using namespace if else for while do return true false void string vector Container");
        List<String> texts = ts.stream().filter(t -> t.type() == TokenType.KEYWORD).map(Token::text).toList();
        assertThat(texts).contains("MODULE", "import", "from", "using", "namespace",
                "if", "else", "for", "while", "do", "return",
                "true", "false", "void", "string", "vector", "Container");
    }

    // ===================== 数字 =====================

    @Test
    void integerLiteral() {
        List<Token> ts = lex("42 007");
        assertThat(ts.get(0).type()).isEqualTo(TokenType.INTEGER);
        assertThat(ts.get(0).text()).isEqualTo("42");
        assertThat(ts.get(1).type()).isEqualTo(TokenType.INTEGER);
        assertThat(ts.get(1).text()).isEqualTo("007");
    }

    @Test
    void floatLiteral() {
        List<Token> ts = lex("3.14");
        assertThat(ts.get(0).type()).isEqualTo(TokenType.FLOAT);
        assertThat(ts.get(0).text()).isEqualTo("3.14");
    }

    // ===================== 字符串 =====================

    @Test
    void stringLiteral() {
        List<Token> ts = lex("MODULE(\"smelting\")");
        List<Token> str = ts.stream().filter(t -> t.type() == TokenType.STRING).toList();
        assertThat(str).hasSize(1);
        assertThat(str.get(0).text()).isEqualTo("smelting");
    }

    @Test
    void unterminatedStringReportsL02() {
        lex("MODULE(\"smelting");
        assertThat(errors("MODULE(\"smelting")).extracting(e -> e.code())
                .contains(ErrorCode.L02_UNTERMINATED_STRING);
    }

    // ===================== 运算符 =====================

    @Test
    void operators() {
        List<Token> ts = lex("+ - * / % = == != < > <= >= && || ! :: . , ;");
        List<TokenType> types = ts.stream().filter(t -> t.type() != TokenType.EOF).map(Token::type).toList();
        assertThat(types).containsExactly(
                TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT,
                TokenType.ASSIGN, TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.GT,
                TokenType.LE, TokenType.GE, TokenType.AND, TokenType.OR, TokenType.NOT,
                TokenType.COLON2, TokenType.DOT, TokenType.COMMA, TokenType.SEMICOLON);
    }

    // ===================== 分隔符 =====================

    @Test
    void delimiters() {
        List<Token> ts = lex("( ) { } [ ]");
        List<TokenType> types = ts.stream().filter(t -> t.type() != TokenType.EOF).map(Token::type).toList();
        assertThat(types).containsExactly(
                TokenType.LPAREN, TokenType.RPAREN, TokenType.LBRACE, TokenType.RBRACE,
                TokenType.LBRACKET, TokenType.RBRACKET);
    }

    // ===================== 注释 =====================

    @Test
    void lineCommentSkipped() {
        List<Token> ts = lex("int a; // 注释\nint b;");
        assertThat(ts.stream().anyMatch(t -> t.type() == TokenType.LINE_COMMENT)).isFalse();
        assertThat(ts).hasSizeGreaterThanOrEqualTo(5); // 不含注释 token
    }

    @Test
    void blockCommentSkipped() {
        List<Token> ts = lex("/* 块注释 */ int a;");
        assertThat(ts.stream().anyMatch(t -> t.type() == TokenType.BLOCK_COMMENT)).isFalse();
    }

    // ===================== 错误码 / 定位 =====================

    @Test
    void unknownSymbolReportsL05WithLocation() {
        lex("int a; $");
        List<CompileError> errs = errors("int a; $");
        assertThat(errs).extracting(e -> e.code()).contains(ErrorCode.L05_UNKNOWN_SYMBOL);
        assertThat(errs.get(0).pos().fileName()).isEqualTo("test.mc");
        assertThat(errs.get(0).pos().line()).isEqualTo(1);
    }

    @Test
    void errorLocationTracksLine() {
        lex("int a;\nint $ b;");
        List<CompileError> errs = errors("int a;\nint $ b;");
        assertThat(errs).extracting(e -> e.code()).contains(ErrorCode.L05_UNKNOWN_SYMBOL);
        assertThat(errs.get(0).pos().line()).isEqualTo(2);
    }

    @Test
    void controlCharReportsL01() {
        // 控制字符不属于"符号写错"，是源码里混进了不该有的字节：仍报 L01。
        List<CompileError> errs = errors("int a;\u0007");
        assertThat(errs).extracting(e -> e.code()).contains(ErrorCode.L01_ILLEGAL_CHAR);
    }

    @Test
    void numberFollowedByLetterReportsL04() {
        List<CompileError> errs = errors("int x = 12abc;");
        assertThat(errs).extracting(e -> e.code()).contains(ErrorCode.L04_BAD_NUMBER);
    }

    @Test
    void twoDecimalPointsReportL04() {
        List<CompileError> errs = errors("float f = 1.2.3;");
        assertThat(errs).extracting(e -> e.code()).contains(ErrorCode.L04_BAD_NUMBER);
    }

    @Test
    void unterminatedBlockCommentReportsL06() {
        List<CompileError> errs = errors("int a;\n/* 没闭合");
        assertThat(errs).extracting(e -> e.code()).contains(ErrorCode.L06_UNTERMINATED_COMMENT);
    }

    @Test
    void eofTokenPresent() {
        List<Token> ts = lex("");
        assertThat(ts).hasSize(1);
        assertThat(ts.get(0).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void sourcePosFormat() {
        SourcePos p = new SourcePos("test.mc", 3, 7);
        assertThat(p.location()).isEqualTo("test.mc:3");
        assertThat(p.toString()).isEqualTo("test.mc:3:7");
    }
}
