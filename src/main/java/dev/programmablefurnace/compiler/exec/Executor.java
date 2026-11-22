package dev.programmablefurnace.compiler.exec;

import dev.programmablefurnace.compiler.sem.StorageTable;
import dev.programmablefurnace.compiler.syntax.Ast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行器：AST 解释器（草稿 §3.8 执行器）。对一个 EXECUTABLE 模块执行其 `RunLoop` 函数体。
 * <ul>
 *   <li>程序逻辑（算术 / 控制流 / 局部作用域 / 状态）由本解释器执行；</li>
 *   <li>机器原语（槽位读写 / 物品 / 容器 / 外部函数）全部委托 {@link Env}，由游戏端实现
 *       ——运行期才做"能否进槽"等判断（§2.5）；</li>
 *   <li>存储表变量（模块级 + 命名空间级）在构造时初始化并**跨 tick 持久**（状态持久，§3.8），
 *       支持烧炼进度等累计状态；</li>
 *   <li>无 RunLoop（依赖库文件）→ {@link ExecResult.Status#NO_ENTRY}。</li>
 * </ul>
 * 运行期错误（未声明变量 / 非法运算 / 机器原语异常）不归入编译失败（§4.6）。
 */
public final class Executor {

    private final Ast.FuncDecl entryTick;       // 常驻入口 RunLoop(int tick)（可为 null）
    private final Ast.FuncDecl entrySignal;     // 事件入口 RunLoop()（可为 null）
    private final Map<String, Ast.FuncDecl> functions = new HashMap<>(); // 本地函数
    private final Map<String, Object> globals = new LinkedHashMap<>();   // 模块级持久变量
    private final Env env;

    /**
     * 单次 tick 的语句/调用步数上限。RunLoop 跑在服务端主线程上，`while (true) {}` 或死循环调用
     * 会永久占住 tick 线程（不是"慢"，是卡死整个服务器），所以必须有硬预算。
     */
    private static final long MAX_STEPS_PER_TICK = 200_000L;

    /** {@link #builtinValue} / {@link #builtinMethod} 的"不是我管的"哨兵（避免用 null 当哨兵）。 */
    private static final Object NOT_BUILTIN = new Object();

    /** 脚本函数调用嵌套上限（递归 / 互递归）：兜住 Java 栈，超限按运行期错误中止本次 tick。 */
    private static final int MAX_CALL_DEPTH = 64;

    private long steps;      // 本次 tick 已执行步数
    private int callDepth;   // 当前调用嵌套深度

    private Executor(Ast.FuncDecl entryTick, Ast.FuncDecl entrySignal, Env env) {
        this.entryTick = entryTick;
        this.entrySignal = entrySignal;
        this.env = env;
    }

    /**
     * 由模块声明列表 + 存储表构建运行器。
     * @param decls 模块 AST 顶层声明
     * @param table 存储表（用于取模块名等，目前主要用于登记引用）
     * @param env   机器原语环境
     */
    public static Executor of(List<Ast.Decl> decls, StorageTable table, Env env) {
        List<Ast.FuncDecl> entries = new ArrayList<>();
        collectEntries(decls, entries);
        Ast.FuncDecl tickEntry = null;
        Ast.FuncDecl signalEntry = null;
        for (Ast.FuncDecl f : entries) {
            // 有形参（约定第一个 int 是游戏刻）→ 常驻；无参 → 红石事件入口
            if (f.params.isEmpty()) {
                if (signalEntry == null) signalEntry = f;
            } else if (tickEntry == null) {
                tickEntry = f;
            }
        }
        Executor e = new Executor(tickEntry, signalEntry, env);
        e.indexFunctions(decls);
        e.initGlobals(decls, new ArrayDeque<>());
        return e;
    }

    /** 执行一次常驻入口（`RunLoop(int tick)`），一个 tick 一次。 */
    public ExecResult tick() {
        return run(entryTick, true);
    }

    /**
     * 执行一次事件入口（`RunLoop()`，无参）——只由外部事件触发（当前是方块收到红石上升沿）。
     *
     * <p>与 {@link #tick()} 分开是刻意的：常驻入口每 tick 都跑，事件入口只在事件发生时跑一次，
     * 两者用形参个数区分（有 `int tick` 才是常驻）。文件里没有无参入口时返回
     * {@link ExecResult.Status#NO_ENTRY}，调用方据此跳过（不算错误）。
     */
    public ExecResult trigger() {
        return run(entrySignal, false);
    }

    /** 两个入口共用的执行骨架。 */
    private ExecResult run(Ast.FuncDecl entry, boolean supplyTime) {
        if (entry == null) {
            return ExecResult.noEntry("<module>");
        }
        steps = 0;
        callDepth = 0;
        try {
            // 每次执行用独立局部作用域，根帧携带模块级变量（跨 tick 持久）。
            // 常驻入口的第一个 int 形参按约定携带当前游戏刻（env.gameTime()），其余补类型默认值；
            // 入口不走 callUser，不补的话 RunLoop(int tick) 里读 tick 会报"未声明变量"。
            Frame root = new Frame(null, globals);
            long now = supplyTime ? env.gameTime() : 0L;
            for (int i = 0; i < entry.params.size(); i++) {
                Ast.Param p = entry.params.get(i);
                String paramType = p.type == null ? "auto" : p.type.name;
                Object value = (i == 0 && "int".equals(paramType)) ? Long.valueOf(now) : defaultValue(paramType);
                root.declare(p.name, value);
            }
            execBlock(entry.body, root);
            return ExecResult.success();
        } catch (ReturnSignal r) {
            return ExecResult.success();   // RunLoop 为 void，return 值忽略
        } catch (BudgetExceeded ex) {
            return ExecResult.error(ex.getMessage());   // 超预算：本次执行中止，下次重新计数
        } catch (StackOverflowError ex) {
            // Error 不会进下面的 RuntimeException 分支；漏出去会把服务端 ticker 打死
            return ExecResult.error("脚本递归过深，已中止本次执行");
        } catch (RuntimeException ex) {
            return ExecResult.error(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /** 计入一步执行预算；超预算抛 {@link BudgetExceeded}（脚本失控，不是编译错误）。 */
    private void chargeStep() {
        if (++steps > MAX_STEPS_PER_TICK) {
            throw new BudgetExceeded("脚本超过单 tick 执行预算（" + MAX_STEPS_PER_TICK + " 步），已中止本次执行");
        }
    }

    /** 脚本失控（死循环 / 超预算）专用的中止信号：与普通运行期错误分开，便于日志区分。 */
    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String message) { super(message); }
    }

    /** 读取全局（存储表）变量的当前值，供外部/测试观察持久状态。 */
    public Object variable(String name) { return globals.get(name); }

    // ===================== 初始化 =====================

    /** 收集所有 `RunLoop` 声明（可跨 namespace），常驻/事件两类由调用方按形参个数区分。 */
    private static void collectEntries(List<Ast.Decl> decls, List<Ast.FuncDecl> out) {
        for (Ast.Decl d : decls) {
            if (d instanceof Ast.FuncDecl f && f.isRunLoop) out.add(f);
            if (d instanceof Ast.NamespaceDecl ns) collectEntries(ns.body, out);
        }
    }

    private void indexFunctions(List<Ast.Decl> decls) {
        for (Ast.Decl d : decls) {
            if (d instanceof Ast.FuncDecl f) functions.put(f.name, f);
            else if (d instanceof Ast.NamespaceDecl ns) indexFunctions(ns.body);
        }
    }

    private void initGlobals(List<Ast.Decl> decls, Deque<String> path) {
        for (Ast.Decl d : decls) {
            if (d instanceof Ast.TopLevelVarDecl tv) {
                Object v = evalInit(tv.var, new Frame(null, null));
                globals.put(tv.var.name, v);
            } else if (d instanceof Ast.NamespaceDecl ns) {
                path.push(ns.name);
                initGlobals(ns.body, path);
                path.pop();
            }
        }
    }

    private Object evalInit(Ast.VarDeclStmt v, Frame frame) {
        if (v.init == null) return defaultValue(v.type.name);
        return eval(v.init, frame);
    }

    private static Object defaultValue(String type) {
        if (type == null) return null;
        switch (type) {
            case "int": return 0L;
            case "float": case "double": return 0.0;
            case "bool": return Boolean.FALSE;
            case "string": case "char": return "";
            default: break;
        }
        // 复合类型：声明但不初始化时给一个空值，别让后面第一次访问就撞 null
        // （`map<string,int> m; m["a"] = 1;` 是最常见的写法）。
        if (type.startsWith("map")) return new Values.MapValue();
        if (type.startsWith("pair")) return new Values.Pair(null, null);
        if (type.startsWith("tuple")) return new Values.Tuple(List.of());
        return null;   // 容器 / auto / 不透明 → null（惰性）
    }

    // ===================== 语句执行 =====================

    private void execBlock(Ast.BlockStmt b, Frame parent) {
        Frame f = new Frame(parent, null);
        for (Ast.Stmt s : b.stmts) execStmt(s, f);
    }

    private void execStmt(Ast.Stmt s, Frame f) {
        chargeStep();
        if (s instanceof Ast.BlockStmt b) { execBlock(b, f); }
        else if (s instanceof Ast.VarDeclStmt v) {
            f.declare(v.name, evalInit(v, f));
        } else if (s instanceof Ast.IfStmt i) {
            if (truthy(eval(i.cond, f))) execStmt(i.thenBranch, f);
            else if (i.elseBranch != null) execStmt(i.elseBranch, f);
        } else if (s instanceof Ast.WhileStmt w) {
            while (truthy(eval(w.cond, f))) {
                try { execStmt(w.body, f); } catch (BreakSignal bs) { break; } catch (ContinueSignal cs) { /* 回 while 条件重判 */ }
            }
        } else if (s instanceof Ast.DoWhileStmt dw) {
            do {
                try { execStmt(dw.body, f); } catch (BreakSignal bs) { break; } catch (ContinueSignal cs) { /* 回 while(cond) 重判 */ }
            } while (truthy(eval(dw.cond, f)));
        } else if (s instanceof Ast.ForStmt fo) {
            Frame ff = new Frame(f, null);
            if (fo.init != null) execStmt(fo.init, ff);
            while (fo.cond == null || truthy(eval(fo.cond, ff))) {
                try { execStmt(fo.body, ff); } catch (BreakSignal bs) { break; } catch (ContinueSignal cs) { /* continue：仍执行增量 */ }
                if (fo.incr != null) eval(fo.incr, ff);
            }
        } else if (s instanceof Ast.RangeForStmt r) {
            Object iter = eval(r.iterable, f);
            List<?> items = env.iterable(iter);
            for (Object item : items) {
                Frame ff = new Frame(f, null);
                ff.declare(r.var, item);
                try { execStmt(r.body, ff); } catch (BreakSignal bs) { break; } catch (ContinueSignal cs) { /* 下一个 item */ }
            }
        } else if (s instanceof Ast.SwitchStmt sw) {
            Object subj = eval(sw.subject, f);
            Ast.SwitchCase matched = null;
            for (Ast.SwitchCase c : sw.cases) {
                if (c.value == null) { if (matched == null) matched = c; }
                else if (equalsVal(subj, eval(c.value, f))) { matched = c; break; }
            }
            if (matched != null) {
                Frame ff = new Frame(f, null);
                try {
                    for (Ast.Stmt st : matched.stmts) execStmt(st, ff);
                } catch (BreakSignal bs) { /* break 跳出 switch */ }
            }
        } else if (s instanceof Ast.ReturnStmt rt) {
            throw new ReturnSignal(rt.value == null ? null : eval(rt.value, f));
        } else if (s instanceof Ast.BreakStmt) {
            throw new BreakSignal();
        } else if (s instanceof Ast.ContinueStmt) {
            throw new ContinueSignal();
        } else if (s instanceof Ast.ExprStmt e) {
            eval(e.expr, f);
        }
    }

    // ===================== 表达式求值 =====================

    private Object eval(Ast.Expr e, Frame f) {
        if (e == null) return null;
        if (e instanceof Ast.LiteralExpr l) {
            return switch (l.kind) {
                case INTEGER -> Long.parseLong(l.value);
                case FLOAT -> Double.parseDouble(l.value);
                case STRING -> l.value;
                case BOOL -> Boolean.parseBoolean(l.value);
            };
        }
        if (e instanceof Ast.VarExpr v) {
            if (f.contains(v.name)) return f.resolve(v.name);
            // 未声明的裸名 → 仅当机器原语声明存在内置常量/外部全局时回退（如 方块自身）
            if (env.hasExternal(v.name)) return env.read(v.name, null);
            throw new RuntimeException("未声明变量 '" + v.name + "'");
        }
        if (e instanceof Ast.UnaryExpr u) {
            Object o = eval(u.operand, f);
            String op = u.op.text();
            if (op.equals("!")) return !truthy(o);
            if (op.equals("-")) return negate(o);
            throw new RuntimeException("未知一元运算符 '" + op + "'");
        }
        if (e instanceof Ast.BinaryExpr b) return evalBinary(b, f);
        if (e instanceof Ast.AssignExpr a) return evalAssign(a, f);
        if (e instanceof Ast.CallExpr c) return evalCall(c, f);
        if (e instanceof Ast.MemberExpr m) {
            // 命名空间限定成员（如 标签::燃料）在运行期统一按字符串处理（草稿 §4.10.4）
            if (m.isNamespace) return m.member;
            // 成员访问（非调用）：先看纯 JVM 复合值，再交给 Env（对象.槽位 / 外部全局）
            Object target = eval(m.obj, f);
            if (target instanceof Values.Pair p) {
                return switch (m.member) {
                    case "first" -> p.first();
                    case "second" -> p.second();
                    default -> throw new RuntimeException("pair 没有成员 '" + m.member + "'（只有 first / second）");
                };
            }
            return env.read(m.member, target);
        }
        if (e instanceof Ast.SubscriptExpr s) return evalSubscript(s, f);
        if (e instanceof Ast.NewExpr n) {
            List<Object> args = new ArrayList<>();
            for (Ast.Expr a : n.args) args.add(eval(a, f));
            if (n.braced) return new ExternalRef(args);   // 构造/初始化列表 → 不透明列表
            Object builtin = builtinValue(n.type.name, args);
            if (builtin != NOT_BUILTIN) return builtin;
            return env.call(n.type.name, null, args);
        }
        if (e instanceof Ast.BraceListExpr bl) {
            List<Object> items = new ArrayList<>();
            for (Ast.Expr it : bl.items) items.add(eval(it, f));
            return new ExternalRef(items);
        }
        throw new RuntimeException("不支持的表达式节点: " + e.getClass().getSimpleName());
    }

    private Object evalBinary(Ast.BinaryExpr b, Frame f) {
        String op = b.op.text();
        if (op.equals("&&")) {
            return truthy(eval(b.left, f)) && truthy(eval(b.right, f));
        }
        if (op.equals("||")) {
            return truthy(eval(b.left, f)) || truthy(eval(b.right, f));
        }
        Object l = eval(b.left, f), r = eval(b.right, f);
        switch (op) {
            case "+":
                if (l instanceof String || r instanceof String) return toText(l) + toText(r);
                return numAdd(l, r);
            case "-": return numSub(l, r);
            case "*": return numMul(l, r);
            case "/": return numDiv(l, r);
            case "%": return numMod(l, r);
            case "<": return numCmp(l, r) < 0;
            case ">": return numCmp(l, r) > 0;
            case "<=": return numCmp(l, r) <= 0;
            case ">=": return numCmp(l, r) >= 0;
            case "==": return equalsVal(l, r);
            case "!=": return !equalsVal(l, r);
            default: throw new RuntimeException("未知二元运算符 '" + op + "'");
        }
    }

    private Object evalAssign(Ast.AssignExpr a, Frame f) {
        Object r = eval(a.rhs, f);
        if (a.lhs instanceof Ast.VarExpr v) {
            if (!f.assign(v.name, r)) {
                throw new RuntimeException("未声明变量 '" + v.name + "'");
            }
        } else if (a.lhs instanceof Ast.MemberExpr m && !m.isNamespace) {
            Object target = eval(m.obj, f);
            if (target instanceof Values.Pair p) {
                switch (m.member) {
                    case "first" -> p.setFirst(r);
                    case "second" -> p.setSecond(r);
                    default -> throw new RuntimeException("pair 没有成员 '" + m.member + "'（只有 first / second）");
                }
            } else {
                env.write(m.member, target, r);
            }
        } else if (a.lhs instanceof Ast.SubscriptExpr s) {
            // 下标写：map 按键写入；tuple 定长替换；列表/槽组替换第 index 个元素（草稿 §4.10.2）
            Object base = eval(s.base, f);
            Object key = eval(s.index, f);
            if (base instanceof Values.MapValue mv) {
                mv.put(key, r);
            } else if (base instanceof Values.Tuple t) {
                t.items().set(indexVal(key, t.size()), r);
            } else {
                List<Object> list = asList(base);
                list.set(indexVal(key, list.size()), r);
            }
        } else {
            throw new RuntimeException("不支持的赋值目标");
        }
        return r;
    }

    /** 下标读：map 按键、tuple 按下标、其余按列表（vector / 槽组）。 */
    private Object evalSubscript(Ast.SubscriptExpr s, Frame f) {
        Object base = eval(s.base, f);
        Object key = eval(s.index, f);
        if (base instanceof Values.MapValue mv) {
            if (!mv.has(key)) {
                throw new RuntimeException("map 里没有键 " + toText(key) + "（先用 has(k) 判断）");
            }
            return mv.get(key);
        }
        if (base instanceof Values.Tuple t) {
            return t.items().get(indexVal(key, t.size()));
        }
        List<Object> list = asList(base);
        return list.get(indexVal(key, list.size()));
    }

    /** 把运行期值解包成可变列表（ExternalRef 委托须为 List）。 */
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        Object b = o instanceof ExternalRef er ? er.delegate() : o;
        if (!(b instanceof List)) throw new RuntimeException("不可下标: " + o);
        return (List<Object>) b;
    }

    /** 下标整数值 + 越界检查。 */
    private static int indexVal(Object iv, int size) {
        if (!(iv instanceof Number n)) throw new RuntimeException("下标非整数: " + iv);
        int idx = n.intValue();
        if (idx < 0 || idx >= size) throw new RuntimeException("下标越界: " + idx + " (size=" + size + ")");
        return idx;
    }

    private Object evalCall(Ast.CallExpr c, Frame f) {
        chargeStep();
        List<Object> args = new ArrayList<>();
        for (Ast.Expr a : c.args) args.add(eval(a, f));
        if (c.callee instanceof Ast.VarExpr v) {
            Ast.FuncDecl fn = functions.get(v.name);
            if (fn != null) return callUser(fn, args);
            Object builtin = builtinValue(v.name, args);
            if (builtin != NOT_BUILTIN) return builtin;
            return env.call(v.name, null, args);   // 外部 / 内置库函数
        }
        if (c.callee instanceof Ast.MemberExpr m) {
            if (m.isNamespace) {
                return env.call(m.member, null, args);   // ns::fn(...) → 外部限定函数
            }
            Object target = eval(m.obj, f);
            Object builtin = builtinMethod(target, m.member, args);
            if (builtin != NOT_BUILTIN) return builtin;
            return env.call(m.member, target, args);   // obj.method(...)
        }
        if (c.callee instanceof Ast.NewExpr n) {
            // Container(...) 等：Parser 产出的形态是 CallExpr(callee=NewExpr) → 外部构造；
            // 但 map/pair/tuple 是语言自带的值，先走内置构造。
            Object builtin = builtinValue(n.type.name, args);
            if (builtin != NOT_BUILTIN) return builtin;
            return env.call(n.type.name, null, args);
        }
        throw new RuntimeException("不支持的调用目标");
    }

    /**
     * 内置复合值的构造：{@code map()}、{@code pair(a, b)}、{@code tuple(a, b, ...)}。
     *
     * <p>它们不是函数（不进函数表、不吃调用深度预算），是语言自带的值构造；
     * 与游戏无关，所以放在解释器里而不是 {@code Env}。
     */
    private static Object builtinValue(String name, List<Object> args) {
        switch (name) {
            case "map":
                return new Values.MapValue();
            case "pair":
                if (args.size() != 2) {
                    throw new RuntimeException("pair(a, b) 需要 2 个参数（当前 " + args.size() + " 个）");
                }
                return new Values.Pair(args.get(0), args.get(1));
            case "tuple":
                return new Values.Tuple(args);
            default:
                return NOT_BUILTIN;
        }
    }

    /** 复合值的成员方法：只有 map / tuple 有，其余交给 {@code Env}。 */
    private static Object builtinMethod(Object target, String name, List<Object> args) {
        if (target instanceof Values.MapValue mv) {
            switch (name) {
                case "size":
                    return (long) mv.size();
                case "has":
                    return mv.has(arg(args, 0, "has(k)"));
                case "get":
                    Object key = arg(args, 0, "get(k)");
                    return mv.has(key) ? mv.get(key) : null;
                case "put":
                    mv.put(arg(args, 0, "put(k, v)"), arg(args, 1, "put(k, v)"));
                    return null;
                case "remove":
                    return mv.entries().remove(arg(args, 0, "remove(k)")) != null;
                case "keys":
                    return mv.keys();
                default:
                    return NOT_BUILTIN;
            }
        }
        if (target instanceof Values.Tuple t) {
            if ("size".equals(name)) return (long) t.size();
            return NOT_BUILTIN;
        }
        return NOT_BUILTIN;
    }

    private static Object arg(List<Object> args, int index, String signature) {
        if (index >= args.size()) {
            throw new RuntimeException(signature + " 缺少第 " + (index + 1) + " 个参数");
        }
        return args.get(index);
    }

    private Object callUser(Ast.FuncDecl fn, List<Object> args) {
        if (++callDepth > MAX_CALL_DEPTH) {
            callDepth--;
            throw new RuntimeException("脚本调用嵌套过深（>" + MAX_CALL_DEPTH + " 层）");
        }
        try {
            Frame f = new Frame(null, globals);
            for (int i = 0; i < fn.params.size(); i++) {
                String pn = fn.params.get(i).name;
                Object av = i < args.size() ? args.get(i) : defaultValue(fn.params.get(i).type.name);
                f.declare(pn, av);
            }
            try {
                execBlock(fn.body, f);
                return null;
            } catch (ReturnSignal r) {
                return r.value;
            }
        } finally {
            callDepth--;
        }
    }

    // ===================== 数值 / 布尔辅助 =====================

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        return true;
    }

    private static Object negate(Object o) {
        if (o instanceof Long l) return -l;
        if (o instanceof Double d) return -d;
        throw new RuntimeException("'-' 操作数非数值: " + o);
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        throw new RuntimeException("非数值操作数: " + v);
    }

    private static Object numAdd(Object l, Object r) {
        if (l instanceof Double || r instanceof Double) return toDouble(l) + toDouble(r);
        return ((Number) l).longValue() + ((Number) r).longValue();
    }
    private static Object numSub(Object l, Object r) {
        if (l instanceof Double || r instanceof Double) return toDouble(l) - toDouble(r);
        return ((Number) l).longValue() - ((Number) r).longValue();
    }
    private static Object numMul(Object l, Object r) {
        if (l instanceof Double || r instanceof Double) return toDouble(l) * toDouble(r);
        return ((Number) l).longValue() * ((Number) r).longValue();
    }
    private static Object numDiv(Object l, Object r) {
        if (l instanceof Double || r instanceof Double) return toDouble(l) / toDouble(r);
        return ((Number) l).longValue() / ((Number) r).longValue();
    }
    private static Object numMod(Object l, Object r) {
        if (l instanceof Double || r instanceof Double) return toDouble(l) % toDouble(r);
        return ((Number) l).longValue() % ((Number) r).longValue();
    }
    private static int numCmp(Object l, Object r) {
        return Double.compare(toDouble(l), toDouble(r));
    }
    private static String toText(Object v) {
        if (v instanceof ExternalRef e) return e.toString();
        return String.valueOf(v);
    }
    private static boolean equalsVal(Object l, Object r) {
        if (l instanceof ExternalRef a && r instanceof ExternalRef b) {
            return java.util.Objects.equals(a.delegate(), b.delegate());
        }
        if (l instanceof Number && r instanceof Number) return toDouble(l) == toDouble(r);
        return java.util.Objects.equals(l, r);
    }

    // ===================== 作用域帧 =====================

    /** 运行时局部作用域（函数形参 / 块级 / 循环变量）。根帧携带存储表变量（globals）。 */
    private static final class Frame {
        private final Frame parent;
        private final Map<String, Object> globals;   // 仅根帧持有（可为 null）
        private final Map<String, Object> vars = new HashMap<>();
        Frame(Frame parent, Map<String, Object> globals) { this.parent = parent; this.globals = globals; }

        void declare(String n, Object v) { vars.put(n, v); }

        boolean contains(String n) {
            if (vars.containsKey(n)) return true;
            if (globals != null && globals.containsKey(n)) return true;
            return parent != null && parent.contains(n);
        }

        boolean assign(String n, Object v) {
            if (vars.containsKey(n)) { vars.put(n, v); return true; }
            if (globals != null && globals.containsKey(n)) { globals.put(n, v); return true; }
            return parent != null && parent.assign(n, v);
        }

        Object resolve(String n) {
            if (vars.containsKey(n)) return vars.get(n);
            if (globals != null && globals.containsKey(n)) return globals.get(n);
            return parent == null ? null : parent.resolve(n);
        }
    }

    /** 用户 return 信号（RunLoop 为 void，外层视为正常结束）。 */
    private static final class ReturnSignal extends RuntimeException {
        final Object value;
        ReturnSignal(Object value) { this.value = value; }
    }

    /** break 跳出信号（最近的 switch / 循环捕获）。 */
    private static final class BreakSignal extends RuntimeException {
        BreakSignal() { super(null, null, false, false); }
    }

    /** continue 信号（最近循环捕获，跳到下一轮迭代，不跳出循环）。 */
    private static final class ContinueSignal extends RuntimeException {
        ContinueSignal() { super(null, null, false, false); }
    }
}
