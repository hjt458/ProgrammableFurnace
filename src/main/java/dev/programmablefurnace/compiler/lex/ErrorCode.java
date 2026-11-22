package dev.programmablefurnace.compiler.lex;

/**
 * 编译期错误码（草稿 §4.7）。
 * <p>编码风格：字母类别前缀 + 数字，按编译阶段归类。只覆盖编译期错误，运行期错误另行处理。</p>
 * <p>类别：L 词法 / S 语法 / T 类型语义 / M 模块导入 / O 优化。</p>
 * <p>每个错误附定位信息（文件名 + 行号），见 {@link CompileError}。</p>
 */
public enum ErrorCode {

    // ===== L 词法（Lex） =====
    /** L01 非法字符：遇到不属于本语言字符集的字符。 */
    L01_ILLEGAL_CHAR,
    /** L02 字符串未闭合：字符串缺少结束引号。 */
    L02_UNTERMINATED_STRING,
    /** L04 数字格式错误：数字字面量格式非法。 */
    L04_BAD_NUMBER,
    /** L05 未知符号：无法归类的符号。 */
    L05_UNKNOWN_SYMBOL,
    /** L06 块注释未闭合：块注释开到文件末尾仍未闭合。 */
    L06_UNTERMINATED_COMMENT,

    // ===== S 语法（Syntax） =====
    /** S01 语句结构错误：语句结构不符合语法。 */
    S01_BAD_STATEMENT,
    /** S02 括号不匹配：括号/花括号/方括号配对错误。 */
    S02_UNMATCHED_BRACKET,
    /** S03 缺少分号：语句结尾缺少分号。 */
    S03_MISSING_SEMICOLON,
    /** S04 缺少大括号：块结构缺少右花括号。 */
    S04_MISSING_BRACE,
    /** S05 表达式非法：表达式语法非法。 */
    S05_BAD_EXPRESSION,

    // ===== T 类型/语义（Type） =====
    /** T01 类型不匹配：赋值/运算/函数实参类型不符。 */
    T01_TYPE_MISMATCH,
    /** T02 未声明变量：引用了未声明的变量。 */
    T02_UNDECLARED_VARIABLE,
    /** T03 未声明函数：调用了未声明的函数。 */
    T03_UNDECLARED_FUNCTION,
    /** T04 函数重载冲突：重载函数签名冲突。 */
    T04_OVERLOAD_CONFLICT,
    /** T05 变量重定义：同名变量重复定义。 */
    T05_VARIABLE_REDEFINITION,
    /** T06 返回类型不符：函数返回类型与声明不符。 */
    T06_BAD_RETURN_TYPE,
    /** T07 作用域访问越界：访问了不可见作用域的符号。 */
    T07_SCOPE_VIOLATION,

    // ===== M 模块/导入（Module） =====
    /** M01 MODULE("name") 缺失：文件首行未声明模块名。 */
    M01_MISSING_MODULE,
    /** M02 MODULE 重复：模块声明出现多次。 */
    M02_DUPLICATE_MODULE,
    /** M03 import 找不到模块：导入的模块不存在。 */
    M03_MODULE_NOT_FOUND,
    /** M04 导入符号不存在：from 导入的符号在目标模块中不存在。 */
    M04_IMPORT_SYMBOL_NOT_FOUND,
    /** M05 循环导入：模块间 import 形成循环依赖。 */
    M05_CYCLIC_IMPORT,
    /** M06 依赖库文件缺失执行入口：需要执行但缺少 RunLoop 等入口。 */
    M06_MISSING_EXEC_ENTRY;

    // 已删除的两个码（2026-11-17）：L03_ILLEGAL_IDENTIFIER 与 O01_OPTIMIZER_FAILURE。
    // 它们从来没有产出点，输入也构造不出来——错误的码表比没有码表更糟，玩家会去查一个永远不出现的编号。
    // 若将来真需要"标识符不合法"语义，先想清楚是哪条规则、哪段代码产出，再加回来。

    /** 类别前缀字母。 */
    public char prefix() { return name().charAt(0); }

    /** 数字后缀（如 L01 → 1）。 */
    public int num() { return Integer.parseInt(name().substring(1, 3)); }

    /** 完整编码文本，如 `L01`。 */
    public String code() { return name().substring(0, 3); }
}
