package dev.programmablefurnace.base;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每机器配置的纯逻辑 UT（覆盖 {@link MachineConfigs.Machine#canOutput} 与容量读取）。
 * 不依赖静态加载（无需 resources），直接构造 {@code Machine} 实例验证"能否输出"由 JSON 的
 * {@code output_allowed} 驱动。
 */
class MachineConfigsTest {

    @Test
    void runtimeAndDependencyOutputAllowedByDefault() {
        // 默认 compiler/furnace_config.json 均配 ["runtime","dependency"]
        MachineConfigs.Machine m = new MachineConfigs.Machine(27, List.of("runtime", "dependency"), "code.txt");
        assertThat(m.canOutput(FileType.RUNTIME)).isTrue();
        assertThat(m.canOutput(FileType.DEPENDENCY)).isTrue();
    }

    @Test
    void nonAllowedTypesRejected() {
        MachineConfigs.Machine m = new MachineConfigs.Machine(27, List.of("runtime", "dependency"), null);
        assertThat(m.canOutput(FileType.BEGIN)).isFalse();
        assertThat(m.canOutput(FileType.END)).isFalse();
    }

    @Test
    void emptyOutputAllowedBlocksEverything() {
        MachineConfigs.Machine m = new MachineConfigs.Machine(27, List.of(), null);
        assertThat(m.canOutput(FileType.RUNTIME)).isFalse();
        assertThat(m.canOutput(FileType.DEPENDENCY)).isFalse();
    }

    @Test
    void nullOutputAllowedBlocksEverything() {
        MachineConfigs.Machine m = new MachineConfigs.Machine(27, null, null);
        assertThat(m.canOutput(FileType.RUNTIME)).isFalse();
        assertThat(m.canOutput(FileType.DEPENDENCY)).isFalse();
    }

    @Test
    void capacityReturnedAsConfigured() {
        MachineConfigs.Machine m = new MachineConfigs.Machine(12, List.of("runtime"), null);
        assertThat(m.capacity()).isEqualTo(12);
    }

    @Test
    void defaultFileNameReturned() {
        MachineConfigs.Machine m = new MachineConfigs.Machine(27, List.of("runtime"), "my_code.txt");
        assertThat(m.defaultFileName()).isEqualTo("my_code.txt");
    }
}
