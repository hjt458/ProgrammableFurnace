package dev.programmablefurnace.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * gui_config.json 的加载与校验。
 *
 * <p>这组测试想守住的事情只有一条：**配置坏了要在加载时说清楚哪里坏了**。Gson 把缺失的 int 读成 0、
 * 把缺失的数组读成 null，这类问题如果留到运行期，表现是"按钮点不到"或"打开界面就崩"，
 * 而堆栈里只有渲染框架的名字。所以每个用例都断言错误消息里带上了 JSON 路径。</p>
 */
class GuiConfigTest {

    private static String shippedJson() throws Exception {
        try (InputStream in = GuiConfigTest.class.getClassLoader()
                .getResourceAsStream("programmablefurnace/gui_config.json")) {
            assertThat(in).as("打包进 jar 的 gui_config.json 必须在测试类路径上").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void shippedConfig_passesValidation() throws Exception {
        GuiConfig.GuiConfigData data = GuiConfig.parse(shippedJson());

        assertThat(data.layout).isNotNull();
        assertThat(data.modes).containsKeys("111", "212", "313", "414");
        assertThat(data.layout.extra.x).hasSameSizeAs(data.layout.extra.y);
        assertThat(data.layout.panel.w).isPositive();
    }

    @Test
    void shippedConfig_modeCountsMatchTheirArrays() throws Exception {
        GuiConfig.GuiConfigData data = GuiConfig.parse(shippedJson());

        GuiConfig.GuiMode m = data.modes.get("414");
        assertThat(m.inputX).hasSize(m.inputCount);
        assertThat(m.outputX).hasSize(m.outputCount);
        assertThat(m.arrowX).hasSize(m.inputCount);
        assertThat(m.inputY).hasSameSizeAs(m.inputX);
    }

    @Test
    void emptyContent_isReported() {
        assertThatThrownBy(() -> GuiConfig.parse(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("文件内容为空");
    }

    @Test
    void missingLayout_isReported() {
        assertThatThrownBy(() -> GuiConfig.parse("{\"modes\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 layout");
    }

    @Test
    void zeroSizedButton_isReportedWithJsonPath() throws Exception {
        String broken = shippedJson().replace("\"btnW\": 40", "\"btnW\": 0");

        assertThatThrownBy(() -> GuiConfig.parse(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("layout.furnace.btnW");
    }

    @Test
    void inputCountMismatch_isReportedWithJsonPath() throws Exception {
        String broken = shippedJson().replace("\"inputX\": [53, 53, 53, 53]", "\"inputX\": [53, 53, 53]");

        assertThatThrownBy(() -> GuiConfig.parse(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modes.414.inputX 长度 3");
    }

    @Test
    void arrowCountMismatch_isReported() throws Exception {
        String broken = shippedJson().replace("\"arrowX\": [79, 79, 79, 79]", "\"arrowX\": [79, 79, 79]");

        assertThatThrownBy(() -> GuiConfig.parse(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arrowX 数量");
    }

    @Test
    void missingMode_isReported() throws Exception {
        String broken = shippedJson().replace("\"313\":", "\"313x\":");

        assertThatThrownBy(() -> GuiConfig.parse(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 modes.313");
    }

    @Test
    void missingArray_isReported() throws Exception {
        String broken = shippedJson().replace("\"inputY\": [8, 26, 44, 62], ", "");

        assertThatThrownBy(() -> GuiConfig.parse(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modes.414.inputY");
    }
}
