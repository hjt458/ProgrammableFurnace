package dev.programmablefurnace.compiler.syntax;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.Lexer;
import dev.programmablefurnace.compiler.lex.SourcePos;
import dev.programmablefurnace.compiler.lex.Token;
import dev.programmablefurnace.compiler.lex.TokenType;
import dev.programmablefurnace.compiler.syntax.Ast.BlockStmt;
import dev.programmablefurnace.compiler.syntax.Ast.Decl;
import dev.programmablefurnace.compiler.syntax.Ast.Expr;
import dev.programmablefurnace.compiler.syntax.Ast.Param;
import dev.programmablefurnace.compiler.syntax.Ast.Stmt;
import dev.programmablefurnace.compiler.syntax.Ast.TypeNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 语法分析器（Parser）：把 Token 流转成 {@link Ast} 语法树（草稿 §2 编译期第②步 + §3 语法要素）。
 * <p>递归下降 + 优先级分层表达式解析。语法错误（S 类错误码）累积于 {@link #errors()}，不中断解析。
 * 纯 JVM 实现，无 MC 依赖。</p>
 */
public final class Parser {

    /**
     * 表达式嵌套上限。递归下降解析器对 `((((...))))` 是真递归，客户端发来的 32KB 源码足够压穿
     * Java 栈（StackOverflowError 会一路冒到服务端 tick 线程），所以在线性解析入口处限深。
     * 超限抛 {@link ParseDepthExceeded}，由 {@code CompilerAnalyzer} 统一转成 FAILED。
     */
    private static final int MAX_EXPR_DEPTH = 128;

    private final List<Token> tokens;
    private int cur = 0;
    private int exprDepth = 0;
    private final List<CompileError> errors = new ArrayList<>();

    /** 表达式嵌套超过 {@link #MAX_EXPR_DEPTH}：属"恶意/畸形源码"，不是普通语法错误。 */
    public static final class ParseDepthExceeded extends RuntimeException {
        public ParseDepthExceeded(int depth) {
            super("表达式嵌套过深（>" + depth + " 层）");
        }
    }

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    // ===================== 入口 =====================

    /** 解析整个文件：返回顶层声明列表（含 MODULE）。 */
    public List<Decl> parseFile() {
        List<Decl> decls = new ArrayList<>();
        while (!check(TokenType.EOF)) {
            int before = cur;
            Decl d = parseTopLevel();
            if (d != null) decls.add(d);
            if (cur == before && !atEnd()) advance(); // 防死循环
        }
        return decls;
    }

    // ===================== 顶层声明 =====================

    private Decl parseTopLevel() {
        if (check(TokenType.KEYWORD)) {
            switch (peek().text()) {
                case "MODULE": return parseModule();
                case "import": return parseImport();
                case "from": return parseFromImport();
                case "using": return parseUsing();
                case "namespace": return parseNamespace();
                case "RunLoop": return parseFunc(true);
            }
        }
        // 函数声明：返回类型开头（可能是类型或 void），用前瞻区分函数与顶层变量
        if (isTypeStart()) {
            return nextIsFunctionDecl() ? parseFunc(false) : parseTopVarDecl();
        }
        // 顶层变量声明：包成 TopLevelVarDecl（Decl），内部为 VarDeclStmt
        return parseTopVarDecl();
    }

    /**
     * 判断当前位置（已是类型起始）是否为函数声明：`类型[<泛型>] 名称 ( ...`。
     * 用于区分顶层"函数声明"与"变量声明"（`int f(){}` vs `int x;`）。泛型括号做嵌套计数扫描，
     * 以便 `vector<Container> 箱子们;` 与 `vector<Container> f(){}` 都判断正确。
     */
    private boolean nextIsFunctionDecl() {
        if (!isTypeStart()) return false;
        int i = cur + 1; // 跳过类型名
        if (i < tokens.size() && tokens.get(i).type() == TokenType.LT) {
            int depth = 1;
            i++;
            while (i < tokens.size() && depth > 0) {
                TokenType t = tokens.get(i).type();
                if (t == TokenType.LT) depth++;
                else if (t == TokenType.GT) depth--;
                i++;
            }
        }
        return isNameAt(i)
                && i + 1 < tokens.size() && tokens.get(i + 1).type() == TokenType.LPAREN;
    }

    /** 名称判定：标识符，或作为执行入口名的关键字 RunLoop（草稿 §3.4）。 */
    private boolean isNameAt(int i) {
        if (i >= tokens.size()) return false;
        TokenType t = tokens.get(i).type();
        if (t == TokenType.IDENTIFIER) return true;
        return t == TokenType.KEYWORD && tokens.get(i).text().equals("RunLoop");
    }

    /** 当前位置是否为合法的名称起始（标识符或 RunLoop 关键字）。 */
    private boolean isNameToken() {
        if (check(TokenType.IDENTIFIER)) return true;
        return check(TokenType.KEYWORD) && peek().text().equals("RunLoop");
    }

    private Decl parseModule() {
        SourcePos p = prev().pos();
        consumeKeyword("MODULE");
        expect(TokenType.LPAREN, "MODULE 后应有 (");
        if (check(TokenType.STRING)) {
            Token nameTok = advance();
            expect(TokenType.RPAREN, "MODULE 名称后应有 )");
            return new Ast.ModuleDecl(p, nameTok.text());
        }
        error(ErrorCode.S01_BAD_STATEMENT, prev().pos(), "MODULE 需要字符串名称");
        synchronize();
        return null;
    }

    private Decl parseImport() {
        SourcePos p = prev().pos();
        consumeKeyword("import");
        if (check(TokenType.IDENTIFIER)) {
            Token mod = advance();
            expect(TokenType.SEMICOLON, "import 语句应以 ; 结尾");
            return new Ast.ImportDecl(p, mod.text());
        }
        error(ErrorCode.S01_BAD_STATEMENT, prev().pos(), "import 后应有模块名");
        synchronize();
        return null;
    }

    private Decl parseFromImport() {
        SourcePos p = prev().pos();
        consumeKeyword("from");
        if (!check(TokenType.IDENTIFIER)) {
            error(ErrorCode.S01_BAD_STATEMENT, prev().pos(), "from 后应有模块名");
            synchronize(); return null;
        }
        String module = advance().text();
        consumeKeyword("import");
        if (check(TokenType.IDENTIFIER)) {
            String symbol = advance().text();
            expect(TokenType.SEMICOLON, "from-import 语句应以 ; 结尾");
            return new Ast.FromImportDecl(p, module, symbol);
        }
        error(ErrorCode.S01_BAD_STATEMENT, prev().pos(), "import 后应有符号名");
        synchronize();
        return null;
    }

    private Decl parseUsing() {
        SourcePos p = prev().pos();
        consumeKeyword("using");
        // using 命名空间::成员;  或  using 新名 = 类型;
        if (check(TokenType.IDENTIFIER)) {
            Token first = advance();
            // 命名空间限定形式
            if (match(TokenType.COLON2)) {
                if (check(TokenType.IDENTIFIER)) {
                    String member = advance().text();
                    expect(TokenType.SEMICOLON, "using 语句应以 ; 结尾");
                    return new Ast.UsingDecl(p, first.text(), member, null, null);
                }
                error(ErrorCode.S05_BAD_EXPRESSION, prev().pos(), "using 命名空间:: 后应有成员名");
                synchronize(); return null;
            }
            // 类型别名形式：using 新名 = 类型;
            if (match(TokenType.ASSIGN)) {
                TypeNode t = parseType();
                expect(TokenType.SEMICOLON, "using 别名应以 ; 结尾");
                return new Ast.UsingDecl(p, null, null, first.text(), t);
            }
            // 单成员引入形式：using 成员;
            expect(TokenType.SEMICOLON, "using 语句应以 ; 结尾");
            return new Ast.UsingDecl(p, null, first.text(), null, null);
        }
        error(ErrorCode.S01_BAD_STATEMENT, prev().pos(), "using 后应有符号");
        synchronize();
        return null;
    }

    private Decl parseNamespace() {
        SourcePos p = prev().pos();
        consumeKeyword("namespace");
        if (!check(TokenType.IDENTIFIER)) {
            error(ErrorCode.S01_BAD_STATEMENT, prev().pos(), "namespace 后应有名称");
            synchronize(); return null;
        }
        String name = advance().text();
        if (!match(TokenType.LBRACE)) {
            error(ErrorCode.S04_MISSING_BRACE, prev().pos(), "namespace 应有 {");
            synchronize(); return null;
        }
        List<Decl> body = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            int before = cur;
            Decl d = parseTopLevel();
            if (d != null) body.add(d);
            if (cur == before && !atEnd()) advance(); // 防死循环
        }
        expect(TokenType.RBRACE, "namespace 应以 } 结尾");
        return new Ast.NamespaceDecl(p, name, body);
    }

    private Decl parseFunc(boolean isRunLoop) {
        SourcePos p = peek().pos();
        TypeNode retType = parseType();
        if (!isNameToken()) {
            error(ErrorCode.S01_BAD_STATEMENT, peek().pos(), "函数缺少名称");
            synchronize(); return null;
        }
        String name = advance().text();
        if (!match(TokenType.LPAREN)) {
            error(ErrorCode.S02_UNMATCHED_BRACKET, prev().pos(), "函数名后应有 (");
            synchronize(); return null;
        }
        List<Param> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                TypeNode pt = parseType();
                if (check(TokenType.IDENTIFIER)) {
                    params.add(new Param(peek().pos(), pt, advance().text()));
                } else {
                    error(ErrorCode.S01_BAD_STATEMENT, peek().pos(), "形参缺少名称");
                    synchronize(); return null;
                }
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RPAREN, "函数形参表应以 ) 结尾");
        if (!match(TokenType.LBRACE)) {
            error(ErrorCode.S04_MISSING_BRACE, prev().pos(), "函数体应有 {");
            synchronize(); return null;
        }
        BlockStmt body = parseBlockRest();
        // 执行入口识别：显式 isRunLoop（RunLoop 开头）或函数名恰为 RunLoop（草稿 §3.4）
        boolean exec = isRunLoop || "RunLoop".equals(name);
        return new Ast.FuncDecl(p, retType, name, params, body, exec);
    }

    /** 顶层变量声明：用 TopLevelVarDecl 包装（因为变量节点属 Stmt 体系，顶层需 Decl）。 */
    private Decl parseTopVarDecl() {
        SourcePos p = peek().pos();
        TypeNode type = parseType();
        if (!check(TokenType.IDENTIFIER)) {
            error(ErrorCode.S01_BAD_STATEMENT, peek().pos(), "变量缺少名称");
            synchronize(); return null;
        }
        String name = advance().text();
        Expr init = null;
        if (match(TokenType.ASSIGN)) init = parseExpr();
        expect(TokenType.SEMICOLON, "变量声明应以 ; 结尾");
        return new Ast.TopLevelVarDecl(p, new Ast.VarDeclStmt(p, type, name, init));
    }

    // ===================== 语句 =====================

    private Stmt parseStmt() {
        if (check(TokenType.KEYWORD)) {
            switch (peek().text()) {
                case "if": return parseIf();
                case "while": return parseWhile();
                case "do": return parseDoWhile();
                case "for": return parseFor();
                case "switch": return parseSwitch();
                case "return": return parseReturn();
                case "break": return parseBreak();
                case "continue": return parseContinue();
            }
        }
        if (match(TokenType.LBRACE)) return parseBlockRest();
        if (isTypeStart()) return parseVarStmt();
        return parseExprStmt();
    }

    private Stmt parseIf() {
        SourcePos p = prev().pos();
        consumeKeyword("if");
        expect(TokenType.LPAREN, "if 后应有 (");
        Expr cond = parseExpr();
        expect(TokenType.RPAREN, "if 条件应以 ) 结尾");
        Stmt thenB = parseStmt();
        Stmt elseB = null;
        if (matchKeyword("else")) elseB = parseStmt();
        return new Ast.IfStmt(p, cond, thenB, elseB);
    }

    private Stmt parseWhile() {
        SourcePos p = prev().pos();
        consumeKeyword("while");
        expect(TokenType.LPAREN, "while 后应有 (");
        Expr cond = parseExpr();
        expect(TokenType.RPAREN, "while 条件应以 ) 结尾");
        Stmt body = parseStmt();
        return new Ast.WhileStmt(p, cond, body);
    }

    private Stmt parseDoWhile() {
        SourcePos p = prev().pos();
        consumeKeyword("do");
        Stmt body = parseStmt();
        consumeKeyword("while");
        expect(TokenType.LPAREN, "do-while 后应有 (");
        Expr cond = parseExpr();
        expect(TokenType.RPAREN, "do-while 条件应以 ) 结尾");
        expect(TokenType.SEMICOLON, "do-while 应以 ; 结尾");
        return new Ast.DoWhileStmt(p, body, cond);
    }

    private Stmt parseFor() {
        SourcePos p = prev().pos();
        consumeKeyword("for");
        expect(TokenType.LPAREN, "for 后应有 (");
        // 范围 for：for (类型 x : expr)
        if (isTypeStart()) {
            TypeNode et = parseType();
            if (check(TokenType.IDENTIFIER)) {
                String var = advance().text();
                if (match(TokenType.COLON)) {
                    Expr iter = parseExpr();
                    expect(TokenType.RPAREN, "范围 for 应以 ) 结尾");
                    Stmt body = parseStmt();
                    return new Ast.RangeForStmt(p, et, var, iter, body);
                }
                // 否则回退为常规 for 的初始化声明（int i = ...; 形式）
                Expr initVal = null;
                if (match(TokenType.ASSIGN)) initVal = parseExpr();
                expect(TokenType.SEMICOLON, "for 初始化后应有 ;");
                return parseForRest(p, new Ast.VarDeclStmt(p, et, var, initVal));
            }
            // 类型后不是标识符 → 按表达式初始化处理
            Stmt stmtInit = null;
            if (!check(TokenType.SEMICOLON)) stmtInit = parseExprStmt();
            else advance();
            return parseForRest(p, stmtInit);
        }
        Stmt init = null;
        if (!check(TokenType.SEMICOLON)) {
            init = parseExprStmt();
        } else {
            advance(); // 空初始化，跳过 ;
        }
        return parseForRest(p, init);
    }

    private Stmt parseForRest(SourcePos p, Stmt init) {
        Expr cond = null;
        if (!check(TokenType.SEMICOLON)) cond = parseExpr();
        expect(TokenType.SEMICOLON, "for 条件后应有 ;");
        Expr incr = null;
        if (!check(TokenType.RPAREN)) incr = parseExpr();
        expect(TokenType.RPAREN, "for 应以 ) 结尾");
        Stmt body = parseStmt();
        return new Ast.ForStmt(p, init, cond, incr, body);
    }

    private Stmt parseSwitch() {
        SourcePos p = prev().pos();
        consumeKeyword("switch");
        expect(TokenType.LPAREN, "switch 后应有 (");
        Expr subject = parseExpr();
        expect(TokenType.RPAREN, "switch 条件应以 ) 结尾");
        expect(TokenType.LBRACE, "switch 应有 {");
        List<Ast.SwitchCase> cases = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            Expr val = null;
            if (matchKeyword("case")) {
                val = parseExpr();
                expect(TokenType.COLON, "case 后应有 :");
            } else if (matchKeyword("default")) {
                expect(TokenType.COLON, "default 后应有 :");
            } else {
                error(ErrorCode.S01_BAD_STATEMENT, peek().pos(), "switch 体内应为 case 或 default");
                synchronize(); break;
            }
            List<Stmt> stmts = new ArrayList<>();
            while (!check(TokenType.RBRACE) && !check(TokenType.EOF) && !isCaseOrDefault()) {
                Stmt s = parseStmt();
                if (s != null) stmts.add(s);
            }
            cases.add(new Ast.SwitchCase(val != null ? val.pos : peek().pos(), val, stmts));
        }
        expect(TokenType.RBRACE, "switch 应以 } 结尾");
        return new Ast.SwitchStmt(p, subject, cases);
    }

    private Stmt parseReturn() {
        SourcePos p = prev().pos();
        consumeKeyword("return");
        Expr val = null;
        if (!check(TokenType.SEMICOLON)) val = parseExpr();
        expect(TokenType.SEMICOLON, "return 应以 ; 结尾");
        return new Ast.ReturnStmt(p, val);
    }

    private Stmt parseBreak() {
        SourcePos p = prev().pos();
        consumeKeyword("break");
        expect(TokenType.SEMICOLON, "break 应以 ; 结尾");
        return new Ast.BreakStmt(p);
    }

    private Stmt parseContinue() {
        SourcePos p = prev().pos();
        consumeKeyword("continue");
        expect(TokenType.SEMICOLON, "continue 应以 ; 结尾");
        return new Ast.ContinueStmt(p);
    }

    private Stmt parseExprStmt() {
        SourcePos p = peek().pos();
        Expr e = parseExpr();
        expect(TokenType.SEMICOLON, "表达式语句应以 ; 结尾");
        if (e == null) return null;   // 表达式已报错：整条语句跳过
        return new Ast.ExprStmt(p, e);
    }

    private BlockStmt parseBlockRest() {
        // 已消费 '{'
        SourcePos p = prev().pos();
        List<Stmt> stmts = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            int before = cur;
            Stmt s = parseStmt();
            if (s != null) stmts.add(s);
            // 防死循环：解析未产生任何 token 进展时强制前进一步
            if (cur == before && !atEnd()) advance();
        }
        expect(TokenType.RBRACE, "块应以 } 结尾");
        return new BlockStmt(p, stmts);
    }

    /** 块内变量声明（Stmt 体系）。 */
    private Stmt parseVarStmt() {
        SourcePos p = peek().pos();
        TypeNode type = parseType();
        if (!check(TokenType.IDENTIFIER)) {
            error(ErrorCode.S01_BAD_STATEMENT, peek().pos(), "变量缺少名称");
            synchronize(); return null;
        }
        String name = advance().text();
        Expr init = null;
        if (match(TokenType.ASSIGN)) init = parseExpr();
        expect(TokenType.SEMICOLON, "变量声明应以 ; 结尾");
        return new Ast.VarDeclStmt(p, type, name, init);
    }

    // ===================== 表达式（优先级分层） =====================

    private Expr parseExpr() {
        if (++exprDepth > MAX_EXPR_DEPTH) {
            exprDepth--;
            throw new ParseDepthExceeded(MAX_EXPR_DEPTH);
        }
        try {
            return parseAssign();
        } finally {
            exprDepth--;
        }
    }

    private Expr parseAssign() {
        Expr left = parseOr();
        if (left == null) return null;   // 子表达式已报错并同步
        if (match(TokenType.ASSIGN)) {
            Expr rhs = parseAssign();
            if (rhs == null) return null;
            return new Ast.AssignExpr(left.pos, left, rhs);
        }
        return left;
    }

    // 优先级链：|| 最低 → && → 相等 → 比较 → 加减 → 乘除 → 一元 → 调用
    private Expr parseOr() { return parseBinary(TokenType.OR, this::parseAnd); }
    private Expr parseAnd() { return parseBinary(TokenType.AND, this::parseEquality); }
    private Expr parseEquality() { return parseBinary(new TokenType[]{TokenType.EQ, TokenType.NE}, this::parseComparison); }
    private Expr parseComparison() { return parseBinary(new TokenType[]{TokenType.LT, TokenType.GT, TokenType.LE, TokenType.GE}, this::parseTerm); }
    private Expr parseTerm() { return parseBinary(new TokenType[]{TokenType.PLUS, TokenType.MINUS}, this::parseFactor); }
    private Expr parseFactor() { return parseBinary(new TokenType[]{TokenType.STAR, TokenType.SLASH, TokenType.PERCENT}, this::parseUnary); }

    /** 左结合二元表达式：ops 中某运算符 → 结合 next 层。 */
    private Expr parseBinary(TokenType op, java.util.function.Supplier<Expr> next) {
        return parseBinary(new TokenType[]{op}, next);
    }

    private Expr parseBinary(TokenType[] ops, java.util.function.Supplier<Expr> next) {
        Expr left = next.get();
        if (left == null) return null;   // 操作数已报错：不再构节点，避免拿 null.pos / null 操作数进 AST
        while (true) {
            if (isOneOf(ops)) {
                Token op = advance();
                Expr right = next.get();
                if (right == null) return null;
                left = new Ast.BinaryExpr(left.pos, left, op, right);
            } else {
                return left;
            }
        }
    }

    private Expr parseUnary() {
        if (check(TokenType.NOT) || check(TokenType.MINUS)) {
            Token op = advance();
            Expr operand = parseUnary();
            if (operand == null) return null;
            return new Ast.UnaryExpr(op.pos(), op, operand);
        }
        return parseCall();
    }

    private Expr parseCall() {
        Expr expr = parsePrimary();
        if (expr == null) return null;   // 主表达式解析失败（已报错并同步）
        while (true) {
            if (match(TokenType.LPAREN)) {
                List<Expr> args = new ArrayList<>();
                if (!check(TokenType.RPAREN)) {
                    do { args.add(parseExpr()); } while (match(TokenType.COMMA));
                }
                expect(TokenType.RPAREN, "函数调用应以 ) 结尾");
                expr = new Ast.CallExpr(expr.pos, expr, args);
            } else if (match(TokenType.DOT)) {
                if (check(TokenType.IDENTIFIER)) {
                    String member = advance().text();
                    expr = new Ast.MemberExpr(expr.pos, expr, member, false);
                } else {
                    error(ErrorCode.S05_BAD_EXPRESSION, peek().pos(), ". 后应为成员名");
                    synchronize(); break;
                }
            } else if (match(TokenType.COLON2)) {
                if (check(TokenType.IDENTIFIER)) {
                    String member = advance().text();
                    expr = new Ast.MemberExpr(expr.pos, expr, member, true);
                } else {
                    error(ErrorCode.S05_BAD_EXPRESSION, peek().pos(), ":: 后应为成员名");
                    synchronize(); break;
                }
            } else if (match(TokenType.LBRACKET)) {
                // 下标访问 base[index]（vector 取元素，草稿 §4.10.2）
                Expr index = parseExpr();
                expect(TokenType.RBRACKET, "下标应以 ] 结尾");
                if (index != null) expr = new Ast.SubscriptExpr(expr.pos, expr, index);
            } else {
                break;
            }
        }
        return expr;
    }

    private Expr parsePrimary() {
        if (match(TokenType.INTEGER)) return new Ast.LiteralExpr(prev().pos(), Ast.LiteralExpr.TokenTypeKind.INTEGER, prev().text());
        if (match(TokenType.FLOAT)) return new Ast.LiteralExpr(prev().pos(), Ast.LiteralExpr.TokenTypeKind.FLOAT, prev().text());
        if (match(TokenType.STRING)) return new Ast.LiteralExpr(prev().pos(), Ast.LiteralExpr.TokenTypeKind.STRING, prev().text());
        if (check(TokenType.KEYWORD) && (peek().text().equals("true") || peek().text().equals("false"))) {
            String v = advance().text();
            return new Ast.LiteralExpr(prev().pos(), Ast.LiteralExpr.TokenTypeKind.BOOL, v);
        }
        if (match(TokenType.LPAREN)) {
            Expr e = parseExpr();
            expect(TokenType.RPAREN, "括号表达式应以 ) 结尾");
            return e;
        }
        // 裸花括号初始化列表 { expr, ... }（作实参 / 初始化，草稿 F7.1 的 init({..},{..},{..})）
        if (match(TokenType.LBRACE)) {
            List<Expr> items = new ArrayList<>();
            if (!check(TokenType.RBRACE)) {
                do { items.add(parseExpr()); } while (match(TokenType.COMMA));
            }
            expect(TokenType.RBRACE, "初始化列表应以 } 结尾");
            return new Ast.BraceListExpr(prev().pos(), items);
        }
        if (check(TokenType.IDENTIFIER)) {
            Token name = advance();
            return new Ast.VarExpr(name.pos(), name.text());
        }
        // 对象构造：vector{...} 等
        if (isTypeStart()) {
            TypeNode t = parseType();
            if (match(TokenType.LBRACE)) {
                List<Expr> args = new ArrayList<>();
                if (!check(TokenType.RBRACE)) {
                    do { args.add(parseExpr()); } while (match(TokenType.COMMA));
                }
                expect(TokenType.RBRACE, "初始化列表应以 } 结尾");
                return new Ast.NewExpr(t.pos, t, args, true);
            }
            return new Ast.NewExpr(t.pos, t, List.of(), false);
        }
        error(ErrorCode.S05_BAD_EXPRESSION, peek().pos(), "无法识别的表达式起始");
        advance(); // 跳过坏 token
        return null;
    }

    // ===================== 类型 =====================

    private boolean isTypeStart() {
        if (check(TokenType.IDENTIFIER)) {
            // 非关键字类型名：`using 新名 = int;` 造出来的别名，以及 `Slot` 这类游戏类型。
            // 判据是"两个标识符连着出现"（类型名 + 变量名），这样 `Slot 槽 = ...` 能解析，
            // 而 `furnace.input[0]` 这种以一个标识符开头的表达式不受影响（下一 token 是 `.`）。
            return peek(1).type() == TokenType.IDENTIFIER;
        }
        if (!check(TokenType.KEYWORD)) return false;
        switch (peek().text()) {
            case "int": case "float": case "double": case "bool": case "char":
            case "void": case "string": case "vector": case "map": case "pair":
            case "tuple": case "auto": case "ItemStream": case "Container":
            case "Item": case "ItemType": // 物品/物品类型（草稿 F2）
                return true;
            default: return false;
        }
    }

    private TypeNode parseType() {
        SourcePos p = peek().pos();
        if (!isTypeStart()) {
            error(ErrorCode.S01_BAD_STATEMENT, p, "期望类型");
            advance();
            return new TypeNode(p, "?", List.of());
        }
        String name = advance().text();
        List<TypeNode> args = new ArrayList<>();
        if (match(TokenType.LT)) {
            do {
                args.add(parseType());
            } while (match(TokenType.COMMA));
            expect(TokenType.GT, "泛型类型应以 > 结尾");
        }
        return new TypeNode(p, name, args);
    }

    // ===================== 工具 =====================

    private boolean isCaseOrDefault() {
        return check(TokenType.KEYWORD) && (peek().text().equals("case") || peek().text().equals("default"));
    }

    private boolean isOneOf(TokenType[] types) {
        if (atEnd()) return false;
        for (TokenType t : types) if (check(t)) return true;
        return false;
    }

    private boolean match(TokenType type) {
        if (check(type)) { advance(); return true; }
        return false;
    }

    private boolean matchKeyword(String kw) {
        if (check(TokenType.KEYWORD) && peek().text().equals(kw)) { advance(); return true; }
        return false;
    }

    private Token consumeKeyword(String kw) {
        if (check(TokenType.KEYWORD) && peek().text().equals(kw)) return advance();
        error(ErrorCode.S01_BAD_STATEMENT, peek().pos(), "期望关键字 " + kw);
        return peek();
    }

    private Token expect(TokenType type, String msg) {
        if (check(type)) return advance();
        error(codeForMissing(type), peek().pos(), msg);
        return peek();
    }

    /**
     * 缺少的 token 决定错误码：以前一律报 S02"括号不匹配"，导致少个分号的人去数括号。
     * 空实现（没有对应错误码的期望）退回 S02，宁可粗也不误报。
     */
    private static ErrorCode codeForMissing(TokenType type) {
        return switch (type) {
            case SEMICOLON -> ErrorCode.S03_MISSING_SEMICOLON;
            case LBRACE, RBRACE -> ErrorCode.S04_MISSING_BRACE;
            case LPAREN, RPAREN, LBRACKET, RBRACKET -> ErrorCode.S02_UNMATCHED_BRACKET;
            default -> ErrorCode.S02_UNMATCHED_BRACKET;
        };
    }

    private boolean check(TokenType type) {
        if (atEnd()) return type == TokenType.EOF; // 允许匹配 EOF，否则 !check(EOF) 恒真导致死循环
        return peek().type() == type;
    }

    private Token advance() {
        if (!atEnd()) cur++;
        return tokens.get(cur - 1);
    }

    private boolean atEnd() { return peek().type() == TokenType.EOF; }

    private Token peek() { return tokens.get(Math.min(cur, tokens.size() - 1)); }
    private Token peek(int ahead) { return tokens.get(Math.min(cur + ahead, tokens.size() - 1)); }
    private Token prev() { return tokens.get(Math.max(0, cur - 1)); }

    /** 出错同步：跳到分号或右花括号。 */
    private void synchronize() {
        while (!atEnd()) {
            if (prev().type() == TokenType.SEMICOLON) return;
            if (peek().type() == TokenType.RBRACE) return;
            advance();
        }
    }

    private void error(ErrorCode code, SourcePos p, String msg) {
        errors.add(new CompileError(code, p, msg));
    }

    /** 收集到的语法错误（S 类）。 */
    public List<CompileError> errors() { return errors; }
}
