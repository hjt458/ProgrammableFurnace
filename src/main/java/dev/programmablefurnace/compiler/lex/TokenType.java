package dev.programmablefurnace.compiler.lex;

/**
 * 记号类型（Token Type）。按草稿 §3 词法/语法要素定义。
 * <p>覆盖：字面量（标识符/关键字/数字/字符串/布尔）、运算符、分隔符、EOF。</p>
 * <p>关键字对应草稿的控制流与类型关键词（MODULE/import/using/类型名等）。</p>
 */
public enum TokenType {

    // 字面量
    IDENTIFIER,     // 标识符（变量/函数/命名空间名）
    KEYWORD,        // 关键字（见下）
    INTEGER,        // 整型字面量
    FLOAT,          // 浮点字面量
    STRING,         // 字符串字面量
    BOOL,           // 布尔字面量 true/false

    // 运算符
    PLUS, MINUS, STAR, SLASH, PERCENT,          // + - * / %
    ASSIGN,                                     // =
    EQ, NE, LT, GT, LE, GE,                      // == != < > <= >=
    AND, OR, NOT,                                // && || !
    AMP,                                         // &（如取引用/位与，占位）

    // 分隔符
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,  // ( ) { } [ ]
    SEMICOLON, COLON, COMMA, DOT,               // ; : , .
    COLON2,                                     // ::（命名空间限定）

    // 注释
    LINE_COMMENT, BLOCK_COMMENT,                // // 与 /* */

    EOF
}
