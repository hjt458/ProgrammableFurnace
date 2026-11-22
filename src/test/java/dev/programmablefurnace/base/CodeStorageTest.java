package dev.programmablefurnace.base;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * CodeStorage 中不依赖 ItemStack 的纯逻辑：
 * {@code classify} 委托当前 {@link CodeAnalyzer}，且分析器可替换（{@code setAnalyzer}）。
 * （依赖 ItemStack 的 NBT 读写方法无法在纯 JVM 单测，交由 GameTest 集成测。）
 */
class CodeStorageTest {

    @Test
    void classify_returnsBlank_forEmptyCode() {
        assertThat(CodeStorage.classify("")).isEqualTo(ContentState.BLANK);
    }

    @Test
    void classify_returnsExecutable_forValidRunLoopSource() {
        assertThat(CodeStorage.classify("MODULE(\"smelting\")\nvoid RunLoop(int tick) { }"))
                .isEqualTo(ContentState.EXECUTABLE);
    }

    @Test
    void classify_returnsFailed_forCompileError() {
        // 语法错误（缺少分号）→ 编译期出错即标失败
        assertThat(CodeStorage.classify("MODULE(\"a\")\nint x")).isEqualTo(ContentState.FAILED);
    }

    @Test
    void setAnalyzer_replacesImplementation_andClassifyFollows() {
        CodeStorage.setAnalyzer(code -> ContentState.FAILED);
        try {
            assertThat(CodeStorage.classify("anything")).isEqualTo(ContentState.FAILED);
        } finally {
            // 恢复默认编译器分析器，避免影响其它测试
            CodeStorage.setAnalyzer(new CompilerAnalyzer());
        }
    }

    @Test
    void setAnalyzer_ignoresNull_keepingDefaultBehavior() {
        CodeStorage.setAnalyzer(null);
        // 传 null 不应覆盖默认实现
        assertThat(CodeStorage.classify("MODULE(\"a\")\nvoid RunLoop(int tick) { }"))
                .isEqualTo(ContentState.EXECUTABLE);
    }

    @Test
    void fileTypeFor_mapsExecutableToRuntime() {
        assertThat(CodeStorage.fileTypeFor(ContentState.EXECUTABLE)).isEqualTo(FileType.RUNTIME);
    }

    @Test
    void fileTypeFor_mapsDependencyToDependency() {
        // 依赖库文件必须带 DEPENDENCY 类型，否则界面会把它当运行库，
        // "依赖不可输出"的规则也就失效了
        assertThat(CodeStorage.fileTypeFor(ContentState.DEPENDENCY)).isEqualTo(FileType.DEPENDENCY);
    }

    @Test
    void fileTypeFor_returnsNull_forStatesWithoutType() {
        assertThat(CodeStorage.fileTypeFor(ContentState.BLANK)).isNull();
        assertThat(CodeStorage.fileTypeFor(ContentState.FAILED)).isNull();
        assertThat(CodeStorage.fileTypeFor(ContentState.READONLY)).isNull();
        assertThat(CodeStorage.fileTypeFor(null)).isNull();
    }
}
