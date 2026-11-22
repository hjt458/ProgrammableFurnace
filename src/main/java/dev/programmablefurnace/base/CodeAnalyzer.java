package dev.programmablefurnace.base;

/**
 * 可编译代码的分析器接口。负责把源代码判定为 {@link ContentState}（空白/可执行/编译失败）。
 *
 * <p>唯一的产品实现是 {@link CompilerAnalyzer}：走词法 + 语法 + 符号绑定真正判定源码能否编译，
 * 早期那个只看首行空白的 {@code PlaceholderCodeAnalyzer} 已删除。保留接口是为了让
 * {@code CodeStorage#setAnalyzer} 能在单测里注入假实现，而不必碰真实编译器。</p>
 */
public interface CodeAnalyzer {

    /** 对源代码做分析，返回其内容状态。 */
    ContentState analyze(String source);
}
