package dev.programmablefurnace.base;

/**
 * {@link CodeAnalyzer} 的占位实现。
 * 当前规则（临时，待 scan/parse 替换）：
 * <ul>
 *   <li>空代码 → {@link ContentState#BLANK}</li>
 *   <li>代码首行非空白 → {@link ContentState#EXECUTABLE}</li>
 *   <li>代码首行为空白 → {@link ContentState#FAILED}</li>
 * </ul>
 */
public final class PlaceholderCodeAnalyzer implements CodeAnalyzer {

    @Override
    public ContentState analyze(String source) {
        if (source == null || source.isEmpty()) return ContentState.BLANK;
        String first = source.split("\\n", 2)[0].trim();
        return first.isEmpty() ? ContentState.FAILED : ContentState.EXECUTABLE;
    }
}
