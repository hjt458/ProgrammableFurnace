package dev.programmablefurnace.compiler.sem;

import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.SourcePos;
import dev.programmablefurnace.compiler.syntax.Ast;
import java.util.ArrayList;
import java.util.List;

/**
 * 由语法树（AST）构建存储表（草稿 §2.4）。
 * <p>登记 模块名 / 顶层与函数级、块级变量 / 函数（返回类型、形参、是否 RunLoop）/ 类型别名 /
 * 命名空间 / import，按作用域（模块级 + namespace 分区 + 函数级 + 块级）防同名。
 * 构建期错误记入 {@link StorageTable#errors()}（草稿 §4.6）。</p>
 */
public final class StorageBuilder {

    private StorageBuilder() {}

    /** 从已解析的顶层声明列表构建存储表。 */
    public static StorageTable build(List<Ast.Decl> decls) {
        StorageTable t = new StorageTable();
        walkDecls(t, decls);
        // 草稿 §1.1：文件必须以 MODULE("name") 开头；缺失 → M01（M 类编译期错误）
        if (t.moduleName() == null) {
            SourcePos pos = decls.isEmpty()
                    ? new SourcePos("<module>", 1, 1) : decls.get(0).pos;
            t.addError(ErrorCode.M01_MISSING_MODULE, pos, "文件缺 MODULE(\"name\") 声明");
        }
        return t;
    }

    // ===================== 顶层声明 =====================

    private static void walkDecls(StorageTable t, List<Ast.Decl> decls) {
        for (Ast.Decl d : decls) {
            if (d instanceof Ast.ModuleDecl m) {
                if (t.moduleName() != null) {
                    t.addError(ErrorCode.M02_DUPLICATE_MODULE, m.pos, "模块声明重复");
                } else {
                    t.setModuleName(m.name);
                }
            } else if (d instanceof Ast.ImportDecl im) {
                t.addImport(new StorageTable.ImportEntry(im.module, null, true, im.pos));
            } else if (d instanceof Ast.FromImportDecl f) {
                t.addImport(new StorageTable.ImportEntry(f.module, f.symbol, false, f.pos));
            } else if (d instanceof Ast.UsingDecl u) {
                if (u.alias != null) {
                    // using 新名 = 类型;  → 登记类型别名（草稿 §3.2 形式 3）
                    t.addSymbol(Symbol.type(u.alias,
                            u.aliasType == null ? "<unknown>" : u.aliasType.toString(),
                            t.currentScopePath(), u.pos));
                } else if (u.ns != null) {
                    // using 命名空间::成员; → 单成员引入（草稿 §3.2 形式 2）
                    t.addUsingRef(u.ns, u.member, u.pos);
                }
            } else if (d instanceof Ast.NamespaceDecl ns) {
                t.addSymbol(Symbol.ns(ns.name, t.currentScopePath(), ns.pos));
                t.pushScope(StorageTable.ScopeKind.NAMESPACE, ns.name);
                walkDecls(t, ns.body);
                t.popScope();
            } else if (d instanceof Ast.FuncDecl fn) {
                registerFunc(t, fn);
            } else if (d instanceof Ast.TopLevelVarDecl tv) {
                Ast.VarDeclStmt v = tv.var;
                t.addSymbol(Symbol.var(v.name, v.type.toString(), t.currentScopePath(),
                        v.pos, initText(v.init)));
            } else {
                t.addError(ErrorCode.S01_BAD_STATEMENT, d.pos, "无法登记的顶层声明");
            }
        }
    }

    // ===================== 函数 + 函数级/块级作用域 =====================

    private static void registerFunc(StorageTable t, Ast.FuncDecl fn) {
        List<String> params = new ArrayList<>();
        for (Ast.Param p : fn.params) params.add(p.type.toString());
        t.addSymbol(Symbol.func(fn.name, fn.returnType.toString(), t.currentScopePath(),
                fn.pos, params, fn.isRunLoop));

        t.pushScope(StorageTable.ScopeKind.FUNCTION, fn.name);
        for (Ast.Param p : fn.params) {
            t.addSymbol(Symbol.var(p.name, p.type.toString(), t.currentScopePath(), p.pos, null));
        }
        walkStmts(t, fn.body);
        t.popScope();
    }

    /** 遍历函数体，登记块级/局部变量声明（Return / 表达式语句不含声明，忽略）。 */
    private static void walkStmts(StorageTable t, Ast.Stmt s) {
        if (s == null) return;
        if (s instanceof Ast.BlockStmt b) {
            t.pushScope(StorageTable.ScopeKind.BLOCK, "block@" + b.pos.line() + ":" + b.pos.col());
            for (Ast.Stmt st : b.stmts) walkStmts(t, st);
            t.popScope();
        } else if (s instanceof Ast.VarDeclStmt v) {
            t.addSymbol(Symbol.var(v.name, v.type.toString(), t.currentScopePath(),
                    v.pos, initText(v.init)));
        } else if (s instanceof Ast.IfStmt ifs) {
            walkStmts(t, ifs.thenBranch);
            walkStmts(t, ifs.elseBranch);
        } else if (s instanceof Ast.WhileStmt w) {
            walkStmts(t, w.body);
        } else if (s instanceof Ast.DoWhileStmt dw) {
            walkStmts(t, dw.body);
        } else if (s instanceof Ast.ForStmt f) {
            // for 引入一个作用域（含 init 与 body，C++ 语义）
            t.pushScope(StorageTable.ScopeKind.BLOCK, "for@" + f.pos.line() + ":" + f.pos.col());
            walkStmts(t, f.init);
            walkStmts(t, f.body);
            t.popScope();
        } else if (s instanceof Ast.RangeForStmt r) {
            t.pushScope(StorageTable.ScopeKind.BLOCK, "for@" + r.pos.line() + ":" + r.pos.col());
            t.addSymbol(Symbol.var(r.var, r.elemType.toString(), t.currentScopePath(), r.pos, null));
            walkStmts(t, r.body);
            t.popScope();
        } else if (s instanceof Ast.SwitchStmt sw) {
            t.pushScope(StorageTable.ScopeKind.BLOCK, "switch@" + sw.pos.line() + ":" + sw.pos.col());
            for (Ast.SwitchCase c : sw.cases) {
                for (Ast.Stmt st : c.stmts) walkStmts(t, st);
            }
            t.popScope();
        }
    }

    // ===================== 辅助 =====================

    /** 初始化表达式的摘要文本（作符号的"默认值"附加属性）。 */
    private static String initText(Ast.Expr e) {
        if (e == null) return null;
        if (e instanceof Ast.LiteralExpr l) return l.value;
        if (e instanceof Ast.VarExpr v) return v.name;
        if (e instanceof Ast.BraceListExpr bl) return "{" + bl.items.size() + "项}";
        return "<expr>";
    }
}
