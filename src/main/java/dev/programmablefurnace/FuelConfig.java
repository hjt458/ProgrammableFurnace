package dev.programmablefurnace;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * 燃料平衡配置：资源内置 JSON（resources/{@code programmablefurnace/fuel_config.json}），
 * 作者固定、随模组分发、玩家不可见（不依赖运行期生成 config 文件）。
 * 顶层为全局默认（燃时/进度上限/每tick增量/效率），{@code fuels} 以<b>物品为粒度</b>按燃料 ID 分块，
 * 块内四条属性覆盖全局默认。由 {@code ProgrammableFurnaceMod} 注册阶段 {@link #load()} 加载一次，
 * 之后静态访问。引擎点火时按燃料 ID 查 {@link #fuelAttrs(String)} 设值。
 */
public final class FuelConfig {

    private static final String FILE = "programmablefurnace/fuel_config.json";
    private static FuelConfigData DATA = null;

    private FuelConfig() {}

    /** 注册阶段调用：加载燃料配置。 */
    public static void load() {
        try (InputStream in = FuelConfig.class.getClassLoader().getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IllegalStateException("Missing fuel config in resources: " + FILE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            FuelConfigData d = new Gson().fromJson(json, FuelConfigData.class);
            if (d == null || d.fuels == null) throw new IllegalStateException("Invalid fuel config: " + FILE);
            DATA = d;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load fuel config: " + FILE, e);
        }
    }

    /** 按燃料物品 ID 查内置块；未内置返回 null（引擎回退全局默认）。 */
    public static FuelAttrs fuelAttrs(String itemId) {
        if (DATA == null) return null;
        return DATA.fuels.get(itemId);
    }

    /** 按优化拓展档位（1/2/3）查对应的四值；不存在返回 null。 */
    public static FuelAttrs boostAttrs(int tier) {
        if (DATA == null) return null;
        return DATA.boosts.get(String.valueOf(tier));
    }

    /** 未内置燃料的默认燃时。 */
    public static int burnTimeDefault() { return DATA != null ? DATA.burnTime : 800; }
    /** 全局默认燃烧效率（燃料块未写 efficiency 时回退）。 */
    public static double burnEfficiency() { return DATA != null ? DATA.burnEfficiency : 1.0; }
    /** 全局默认进度上限。 */
    public static int maxProgressDefault() { return DATA != null ? DATA.maxProgress : 200; }
    /** 全局默认每 tick 进度增量。 */
    public static int progressPerTickDefault() { return DATA != null ? DATA.progressPerTick : 1; }

    /** 单个燃料块的四条属性。 */
    public static class FuelAttrs {
        @SerializedName("burnTime") int burnTime;
        @SerializedName("efficiency") double efficiency;
        @SerializedName("maxProgress") int maxProgress;
        @SerializedName("progressPerTick") int progressPerTick;
        public int burnTime() { return burnTime; }
        public double efficiency() { return efficiency; }
        public int maxProgress() { return maxProgress; }
        public int progressPerTick() { return progressPerTick; }
    }

    /** 顶层 JSON 数据模型。 */
    static class FuelConfigData {
        @SerializedName("burnTime") int burnTime = 800;
        @SerializedName("maxProgress") int maxProgress = 200;
        @SerializedName("progressPerTick") int progressPerTick = 1;
        @SerializedName("burnEfficiency") double burnEfficiency = 1.0;
        @SerializedName("fuels") Map<String, FuelAttrs> fuels = Collections.emptyMap();
        @SerializedName("boosts") Map<String, FuelAttrs> boosts = Collections.emptyMap();
    }
}
