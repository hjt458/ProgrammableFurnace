package dev.programmablefurnace.base;

/**
 * 可编译代码的分析器接口。负责把源代码判定为 {@link ContentState}（空白/可执行/编译失败）。
 *
 * <p><b>占位说明</b>：当前只提供最朴素的占位实现 {@link PlaceholderCodeAnalyzer}
 * （空→BLANK，首行非空→EXECUTABLE，首行空白→FAILED）。
 * 后续计划引入词法分析（scan）与语法分析（parse）来真正判定代码是否可通过编译，
 * 届时只需提供新的 {@link CodeAnalyzer} 实现并替换注册即可，无需改动调用方。</p>
 */
public interface CodeAnalyzer {

    /** 对源代码做分析，返回其内容状态。 */
    ContentState analyze(String source);
}
