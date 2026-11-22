package dev.programmablefurnace.compiler.sem;

import dev.programmablefurnace.compiler.lex.CompileError;
import dev.programmablefurnace.compiler.lex.ErrorCode;
import dev.programmablefurnace.compiler.lex.SourcePos;
import dev.programmablefurnace.compiler.syntax.Ast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义 / 类型检查（草稿 §2.5、错误码 T 类）。
 * <p>采用保守策略：只对"本地、明确"的类型信息报错，凡涉及内置库 / 导入模块 / 对象方法的
 * 未知类型一律按不透明处理（避免 F6 内置库清单未定稿导致误报）。可可靠检查：</p>
 * <ul>
 *   <li>T01 类型不匹配：已知标量类型变量（int/float/double/bool/string）的初始化、赋值、返回值、
 *       以及布尔 / 算术 / 比较运算符的操作数类型；</li>
 *   <li>T06 返回类型不符：void 函数返回了值。</li>
 *   <li>T07 作用域越界：break / continue 出现在循环或 switch 之外。</li>
 *   <li>T02 未声明变量：读一个全脚本都没声明过的名字（按名字去重，只报一次）。</li>
 * </ul>
 * <p>类型别名（`using 新名 = int;`）在检查前先展开成基类型，否则别名变量会被当成"不透明类型"而漏检。
 * 槽位"能否进槽"的判断按 §2.5 属运行期，编译期不判（脚本用 `槽.canPut(物品)` 在运行期问）。</p>
 */
public final class TypeChecker {

    private final StorageTable table;
    private final List<CompileError> errors = new ArrayList<>();
    /** 本地作用域栈（函数形参 / 块级局部变量），自上而下查找。 */
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
    /** 已报过 T02 的名字，避免同一个拼错的名字在每个使用点刷一遍。 */
    private final Set<String> undeclaredReported = new HashSet<>();
    /** 当前所在函数的返回类型。 */
    private String returnType = "void";
    /** 循环嵌套深度（while / do-while / for / range-for），continue 与 break 都要求它在循环内。 */
    private int loopDepth = 0;
    /** 可 break 的嵌套深度（循环 + switch）：switch 里的 break 是正常写法，不该报错。 */
    private int breakableDepth = 0;

    private TypeChecker(StorageTable table) { this.table = table; }

    /** 对存储表 + AST 执行语义检查。 */
    public static TypeChecker check(StorageTable table, List<Ast.Decl> decls) {
        TypeChecker c = new TypeChecker(table);
        for (Ast.Decl d : decls) c.walkDecl(d);
        return c;
    }

    // ===================== 遍历 =====================

    private void walkDecl(Ast.Decl d) {
        if (d instanceof Ast.FuncDecl f) {
            pushScope();
            for (Ast.Param p : f.params) scopes.peek().put(p.name,
                    Symbol.var(p.name, resolveAlias(p.type.toString()), "", p.pos, null));
            String saved = returnType;
            returnType = resolveAlias(f.returnType.toString());
            // 进入函数体：外层循环/switch 的深度不能借到函数里用（函数体是独立的作用域边界）。
            int savedLoop = loopDepth;
            int savedBreakable = breakableDepth;
            loopDepth = 0;
            breakableDepth = 0;
            walkStmt(f.body);
            loopDepth = savedLoop;
            breakableDepth = savedBreakable;
            returnType = saved;
            popScope();
        } else if (d instanceof Ast.TopLevelVarDecl tv) {
            checkVarDecl(tv.var);
        } else if (d instanceof Ast.NamespaceDecl ns) {
            for (Ast.Decl dd : ns.body) walkDecl(dd);
        }
    }

    private void walkStmt(Ast.Stmt s) {
        if (s == null) return;
        if (s instanceof Ast.BlockStmt b) {
            pushScope();
            for (Ast.Stmt st : b.stmts) walkStmt(st);
            popScope();
        } else if (s instanceof Ast.VarDeclStmt v) {
            scopes.peek().put(v.name, Symbol.var(v.name, v.type.toString(), "", v.pos, null));
            checkVarDecl(v);
        } else if (s instanceof Ast.IfStmt i) {
            walkStmt(i.thenBranch); walkStmt(i.elseBranch);
        } else if (s instanceof Ast.WhileStmt w) {
            walkLoopBody(w.body);
        } else if (s instanceof Ast.DoWhileStmt dw) {
            walkLoopBody(dw.body);
        } else if (s instanceof Ast.ForStmt f) {
            pushScope();
            walkStmt(f.init);
            walkLoopBody(f.body);
            popScope();
        } else if (s instanceof Ast.RangeForStmt r) {
            pushScope();
            scopes.peek().put(r.var, Symbol.var(r.var, r.elemType.toString(), "", r.pos, null));
            walkLoopBody(r.body);
            popScope();
        } else if (s instanceof Ast.SwitchStmt sw) {
            pushScope();
            // switch 里允许 break：只抬高 breakableDepth，不动 loopDepth（continue 在 switch 里无意义）。
            breakableDepth++;
            for (Ast.SwitchCase c : sw.cases) for (Ast.Stmt st : c.stmts) walkStmt(st);
            breakableDepth--;
            popScope();
        } else if (s instanceof Ast.BreakStmt br) {
            if (breakableDepth == 0) {
                errors.add(new CompileError(ErrorCode.T07_SCOPE_VIOLATION, br.pos,
                        "break 只能用在循环或 switch 内"));
            }
        } else if (s instanceof Ast.ContinueStmt ct) {
            if (loopDepth == 0) {
                errors.add(new CompileError(ErrorCode.T07_SCOPE_VIOLATION, ct.pos,
                        "continue 只能用在循环内"));
            }
        } else if (s instanceof Ast.ReturnStmt rt) {
            checkReturn(rt);
        } else if (s instanceof Ast.ExprStmt e) {
            inferType(e.expr);   // 触发赋值语句的赋值类型检查
        }
    }

    // ===================== 检查 =====================

    /** 循环体：进入时抬高两种深度，离开时还原，使 break / continue 只认自己所在的循环。 */
    private void walkLoopBody(Ast.Stmt body) {
        loopDepth++;
        breakableDepth++;
        walkStmt(body);
        breakableDepth--;
        loopDepth--;
    }

    private void checkVarDecl(Ast.VarDeclStmt v) {
        if (v.init == null) return;
        String declared = resolveAlias(v.type.toString());
        if ("auto".equals(declared) || !isKnownScalar(declared)) return;   // auto / 容器 / 不透明类型放宽
        String it = inferType(v.init);
        if (it == null) return;
        if (!assignable(declared, it)) {
            errors.add(new CompileError(ErrorCode.T01_TYPE_MISMATCH, v.pos,
                    "类型不匹配：'" + it + "' 不能赋给 '" + declared + "'"));
        }
    }

    private void checkReturn(Ast.ReturnStmt rt) {
        if ("void".equals(returnType)) {
            if (rt.value != null) {
                errors.add(new CompileError(ErrorCode.T06_BAD_RETURN_TYPE, rt.pos, "void 函数不能返回值"));
            }
            return;
        }
        if (rt.value == null) return;   // 非 void 缺 return 暂不追究
        if (!isKnownScalar(returnType)) return;
        String vt = inferType(rt.value);
        if (vt == null) return;
        if (!assignable(returnType, vt)) {
            errors.add(new CompileError(ErrorCode.T01_TYPE_MISMATCH, rt.pos,
                    "返回类型不匹配：'" + vt + "' 不能返回为 '" + returnType + "'"));
        }
    }

    // ===================== 类型推断（未知返回 null） =====================

    private String inferType(Ast.Expr e) {
        if (e == null) return null;
        if (e instanceof Ast.LiteralExpr l) {
            return switch (l.kind) {
                case INTEGER -> "int"; case FLOAT -> "float"; case STRING -> "string"; case BOOL -> "bool";
            };
        }
        if (e instanceof Ast.VarExpr v) {
            Symbol s = resolve(v.name);
            if (s != null) return s.kind == Symbol.Kind.VAR ? s.type : null;
            reportUndeclared(v);
            return null;
        }
        if (e instanceof Ast.CallExpr c) {
            String fn = calleeName(c.callee);
            if (fn != null) {
                Symbol s = resolve(fn);   // resolve 支持 "::"（本地命名空间函数）
                if (s != null && s.kind == Symbol.Kind.FUNC) return s.type;
            }
            return null;   // 外部函数 / 对象方法 → 未知
        }
        if (e instanceof Ast.UnaryExpr u) {
            String ot = inferType(u.operand);
            if (ot == null) return null;
            if (u.op.text().equals("!")) {
                if (!ot.equals("bool")) { error(ErrorCode.T01_TYPE_MISMATCH, u.pos, "'!' 需要 bool，实为 '" + ot + "'"); return null; }
                return "bool";
            }
            if (u.op.text().equals("-")) {
                if (!isNumeric(ot)) { error(ErrorCode.T01_TYPE_MISMATCH, u.pos, "'-' 需要数值，实为 '" + ot + "'"); return null; }
                return ot;
            }
            return null;
        }
        if (e instanceof Ast.BinaryExpr b) {
            String lt = inferType(b.left), rt = inferType(b.right);
            if (lt == null || rt == null) return null;
            String op = b.op.text();
            if (op.equals("&&") || op.equals("||")) {
                if (!lt.equals("bool") || !rt.equals("bool")) { error(ErrorCode.T01_TYPE_MISMATCH, b.pos, "逻辑运算需要 bool，实为 '" + lt + "' 与 '" + rt + "'"); return null; }
                return "bool";
            }
            if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
                if (!isNumeric(lt) || !isNumeric(rt)) { error(ErrorCode.T01_TYPE_MISMATCH, b.pos, "比较运算需要数值，实为 '" + lt + "' 与 '" + rt + "'"); return null; }
                return "bool";
            }
            if (op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/") || op.equals("%")) {
                if (!arithmetic(lt, rt, op)) { error(ErrorCode.T01_TYPE_MISMATCH, b.pos, "算术运算操作数类型不匹配：'" + lt + "' " + op + " '" + rt + "'"); return null; }
                if (op.equals("+") && lt.equals("string")) return "string";   // 字符串拼接
                return numericResult(lt, rt);
            }
            return null;   // == != 等宽松
        }
        if (e instanceof Ast.AssignExpr a) {
            if (a.lhs instanceof Ast.VarExpr v) {
                Symbol s = resolve(v.name);
                String it = inferType(a.rhs);
                if (s != null && s.kind == Symbol.Kind.VAR && it != null
                        && isKnownScalar(s.type) && !assignable(s.type, it)) {
                    error(ErrorCode.T01_TYPE_MISMATCH, a.pos, "赋值类型不匹配：'" + it + "' → '" + s.type + "'");
                }
            }
            return inferType(a.rhs);
        }
        if (e instanceof Ast.NewExpr n) return n.type.toString();
        return null;   // MemberExpr / BraceListExpr / TernaryExpr 等 → 未知
    }

    // ===================== 辅助 =====================

    private void error(ErrorCode code, SourcePos pos, String msg) { errors.add(new CompileError(code, pos, msg)); }

    private Symbol resolve(String name) {
        for (Map<String, Symbol> scope : scopes) {
            Symbol s = scope.get(name);
            if (s != null) return s;
        }
        return table.resolve(name);
    }

    /** 展开类型别名（`using 新名 = 基类型;`）。别名按链走，最多 8 跳，防止 `using A = B; using B = A;` 转圈。 */
    private String resolveAlias(String name) {
        String cur = name;
        for (int i = 0; i < 8 && cur != null; i++) {
            Symbol s = resolve(cur);
            if (s == null || s.kind != Symbol.Kind.TYPE) return cur;
            String next = s.type;
            if (next == null || next.isBlank() || next.equals(cur)) return cur;
            if (next.startsWith("?") || next.startsWith("<")) return cur;   // 未知类型名（`?` / `<unknown>`）不再展开
            cur = next;
        }
        return cur == null ? name : cur;
    }

    /**
     * 读到一个哪里都没声明过的名字时记 T02。
     * <p>用"全表查一遍"而不是只看当前作用域：脚本允许先把函数写在前面、全局变量写在后面，
     * 按位置判会误报；真正要抓的是拼错的和根本不存在的外部名字。</p>
     */
    private void reportUndeclared(Ast.VarExpr v) {
        if (!undeclaredReported.add(v.name)) return;
        for (Symbol s : table.allSymbols()) {
            if (s.name.equals(v.name)) return;
        }
        error(ErrorCode.T02_UNDECLARED_VARIABLE, v.pos, "未声明变量 '" + v.name + "'");
    }

    private void pushScope() { scopes.push(new HashMap<>()); }
    private void popScope() { scopes.pop(); }

    private static boolean isNumeric(String t) {
        return t.equals("int") || t.equals("float") || t.equals("double");
    }

    private static boolean isKnownScalar(String t) {
        return isNumeric(t) || t.equals("bool") || t.equals("string") || t.equals("char");
    }

    private static boolean assignable(String decl, String src) {
        if (isNumeric(decl)) return isNumeric(src);
        return decl.equals(src);
    }

    private static boolean arithmetic(String a, String b, String op) {
        if (isNumeric(a) && isNumeric(b)) return true;
        return op.equals("+") && a.equals("string") && b.equals("string");   // 字符串拼接
    }

    private static String numericResult(String a, String b) {
        if (a.equals("double") || b.equals("double")) return "double";
        if (a.equals("float") || b.equals("float")) return "float";
        return "int";
    }

    private static String calleeName(Ast.Expr callee) {
        if (callee instanceof Ast.VarExpr v) return v.name;
        if (callee instanceof Ast.MemberExpr m && m.isNamespace) {
            String prefix = m.obj instanceof Ast.VarExpr o ? o.name : null;
            return prefix == null ? null : prefix + "::" + m.member;
        }
        return null;   // 对象方法 / 其他 → 未知
    }

    // ===================== 结果 =====================

    public List<CompileError> errors() { return errors; }
}
