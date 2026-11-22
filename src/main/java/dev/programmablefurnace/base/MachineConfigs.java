package dev.programmablefurnace.base;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 按机器分区的配置：编译器 / 执行器各一个 JSON（resources/{@code programmablefurnace/compiler_config.json}、
 * {@code furnace_config.json}），统一管理各自依赖库的<b>容器容量</b>与<b>输出权限</b>
 * （以及编译器专属的默认文件名），避免硬编码。GUI 布局坐标仍由通用 {@link GuiConfig}(gui_config.json) 管理。
 * <p>由 {@code ProgrammableFurnaceMod} 注册阶段调用 {@link #load()} 加载一次，之后静态访问。
 * 纯 JVM 单测不依赖本类静态加载，可构造 {@link Machine} 实例测 {@link Machine#canOutput}。</p>
 */
public final class MachineConfigs {

    private static final String COMPILER_FILE = "programmablefurnace/compiler_config.json";
    private static final String FURNACE_FILE = "programmablefurnace/furnace_config.json";

    private static Machine COMPILER = null;
    private static Machine FURNACE = null;

    private MachineConfigs() {}

    /** 注册阶段调用：加载两个机器的 JSON 配置。 */
    public static void load() {
        COMPILER = loadMachine(COMPILER_FILE);
        FURNACE = loadMachine(FURNACE_FILE);
    }

    private static Machine loadMachine(String file) {
        try (InputStream in = MachineConfigs.class.getClassLoader().getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("Missing machine config in resources: " + file);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Machine m = new Gson().fromJson(json, Machine.class);
            if (m == null) throw new IllegalStateException("Invalid machine config: " + file);
            return m;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load machine config: " + file, e);
        }
    }

    /** 编译器配置。 */
    public static Machine compiler() {
        return COMPILER;
    }

    /** 执行器配置。 */
    public static Machine furnace() {
        return FURNACE;
    }

    /** 单个机器的配置数据模型（容量 + 输出权限 + 默认文件名）。 */
    public static class Machine {
        @SerializedName("capacity")
        int capacity;
        @SerializedName("output_allowed")
        List<String> outputAllowed;
        @SerializedName("default_file_name")
        String defaultFileName;
        @SerializedName("input_count")
        int inputCount = 1;
        @SerializedName("fuel_count")
        int fuelCount = 1;
        @SerializedName("output_count")
        int outputCount = 1;

        public Machine() {}

        /** 供 UT 构造。 */
        public Machine(int capacity, List<String> outputAllowed, String defaultFileName) {
            this.capacity = capacity;
            this.outputAllowed = outputAllowed;
            this.defaultFileName = defaultFileName;
        }

        /** 依赖库容器容量上限。 */
        public int capacity() {
            return capacity;
        }

        /** 输入槽组槽数（槽位布局，§4.10.2；改 JSON 即换布局，语言与代码不变）。 */
        public int inputCount() { return inputCount; }

        /** 燃料槽组槽数。 */
        public int fuelCount() { return fuelCount; }

        /** 输出槽组槽数。 */
        public int outputCount() { return outputCount; }

        /**
         * 指定文件类型是否允许从依赖库输出。
         * 判定依据 JSON {@code output_allowed} 中列出的 FileType 名（小写）。
         */
        public boolean canOutput(FileType type) {
            if (outputAllowed == null || type == null) return false;
            return outputAllowed.contains(type.name().toLowerCase(Locale.ROOT));
        }

        /** 默认编码文件名（编译器二层编辑器标题兜底；执行器无此配置时返回 null）。 */
        public String defaultFileName() {
            return defaultFileName;
        }
    }
}
