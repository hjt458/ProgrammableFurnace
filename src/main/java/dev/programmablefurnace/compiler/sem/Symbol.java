package dev.programmablefurnace.compiler.sem;

import dev.programmablefurnace.compiler.lex.SourcePos;
import java.util.List;

/**
 * 存储表（符号表）中的一条符号（草稿 §2.4）：{名字 name, 类型 type, 作用域 scope, 附加属性}。
 * <p>作用域指所属 namespace 分区路径（模块根为 {@code <module>}，如 {@code 炉}）。附加属性按种类填充：</p>
 * <ul>
 *   <li>VAR：类型 + 默认值（init，可空）；</li>
 *   <li>FUNC：返回类型 + 形参类型表 + 是否执行函数 {@code RunLoop}；</li>
 *   <li>TYPE：类型别名指向的类型；</li>
 *   <li>NAMESPACE：无附加属性。</li>
 * </ul>
 */
public final class Symbol {

    public enum Kind { VAR, FUNC, TYPE, NAMESPACE }

    public final Kind kind;
    public final String name;
    public final String type;     // 声明类型 / 返回类型 / 别名目标；NAMESPACE 为 null
    public final String scope;    // 所属作用域（namespace 路径）
    public final SourcePos pos;

    // FUNC 附加属性
    public final List<String> paramTypes;  // 形参类型表；非 FUNC 为空
    public final boolean isRunLoop;        // 是否执行函数 RunLoop（草稿 §3.4）

    // VAR 附加属性
    public final String init;              // 默认值 / 初始化摘要，可空

    private Symbol(Kind kind, String name, String type, String scope, SourcePos pos,
                   List<String> paramTypes, boolean isRunLoop, String init) {
        this.kind = kind; this.name = name; this.type = type;
        this.scope = scope; this.pos = pos;
        this.paramTypes = paramTypes; this.isRunLoop = isRunLoop; this.init = init;
    }

    public static Symbol var(String name, String type, String scope, SourcePos pos, String init) {
        return new Symbol(Kind.VAR, name, type, scope, pos, List.of(), false, init);
    }

    public static Symbol func(String name, String type, String scope, SourcePos pos,
                              List<String> paramTypes, boolean isRunLoop) {
        return new Symbol(Kind.FUNC, name, type, scope, pos, paramTypes, isRunLoop, null);
    }

    public static Symbol type(String name, String aliasTarget, String scope, SourcePos pos) {
        return new Symbol(Kind.TYPE, name, aliasTarget, scope, pos, List.of(), false, null);
    }

    public static Symbol ns(String name, String scope, SourcePos pos) {
        return new Symbol(Kind.NAMESPACE, name, null, scope, pos, List.of(), false, null);
    }

    @Override
    public String toString() {
        return kind + " " + name + " : " + (type == null ? "-" : type) + " @" + scope;
    }
}
