package dev.programmablefurnace.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 槽组名 ↔ 槽索引映射（纯 JVM，无 MC 依赖，可单测；草稿 §4.10.2 槽组模型）。
 * <p>槽位按"槽组"组织，不写死单槽：每个槽组是一组<b>按机器配置数量</b>的槽位集合，
 * 数量由机器配置决定、语言不写死（熔炉现 input=1/fuel=1/output=1，未来可配置 input=4/fuel=1/output=4）。
 * 槽组按固定顺序分配连续索引：input → fuel → output → program → dep_input → dep_output →
 * <b>gap（不属于任何槽组的机器专属槽，脚本不可见）</b> → dep 容器区。</p>
 * <p><b>gap 的存在原因</b>：真实机器在 dep_output 之后还有脚本不能碰的槽（如熔炉的 U 盘拓展槽
 * {@code EXTRA_START..EXTRA_START+EXTRA_COUNT-1}），若只按前三组槽数相加推导 depStart，脚本的
 * {@code dep[i]} 就会落到拓展槽上（2026-11-16 修：曾偏移 4 槽，导致脚本可搬走 U 盘卡）。
 * 构造时传真实 gap 槽数即可对齐；槽数由机器常量而非配置决定——配置里的 input_count 只影响"激活多少个"，
 * 不改变槽索引。</p>
 */
public final class SlotNameMap {

    public static final String INPUT = "input";
    public static final String FUEL = "fuel";
    public static final String OUTPUT = "output";
    public static final String PROGRAM = "program";
    public static final String DEP_INPUT = "dep_input";
    public static final String DEP_OUTPUT = "dep_output";
    public static final String DEP = "dep"; // 依赖库容器区（数组式 dep(0..n)）

    /** 槽组固定顺序（与熔炉真实布局 SLOT_INPUT=0 … DEP_START=16 对应，见 gapCount 说明）。 */
    private static final String[] ORDER = {INPUT, FUEL, OUTPUT, PROGRAM, DEP_INPUT, DEP_OUTPUT};

    private final int[] start;  // 每组起始索引
    private final int[] count;  // 每组槽数
    private final int gapCount; // 脚本不可见的机器专属槽数（如拓展槽）
    private final int depStart;
    private final int depCapacity;

    /**
     * @param inputCount 输入槽组槽数
     * @param fuelCount  燃料槽组槽数
     * @param outputCount 输出槽组槽数
     * @param programCount 程序槽组槽数
     * @param depInputCount 依赖库输入槽组槽数
     * @param depOutputCount 依赖库输出槽组槽数
     * @param gapCount 依赖库输出之后、依赖库容器区之前的机器专属槽数（脚本不可见；熔炉为 EXTRA_COUNT）
     * @param depCapacity 依赖库容器区槽数（容量）
     */
    public SlotNameMap(int inputCount, int fuelCount, int outputCount, int programCount,
                       int depInputCount, int depOutputCount, int gapCount, int depCapacity) {
        this.count = new int[]{inputCount, fuelCount, outputCount, programCount, depInputCount, depOutputCount};
        this.start = new int[ORDER.length];
        int s = 0;
        for (int i = 0; i < ORDER.length; i++) {
            start[i] = s;
            s += count[i];
        }
        this.gapCount = Math.max(0, gapCount);
        this.depStart = s + this.gapCount;
        this.depCapacity = Math.max(0, depCapacity);
    }

    /**
     * 槽组名 → 组内所有槽索引。未知组名返回空列表。
     * <p>如 {@code input} → [0..inputCount-1]、{@code dep} → [depStart..depStart+capacity-1]。</p>
     */
    public List<Integer> group(String groupName) {
        String n = normalize(groupName);
        if (n.equals(DEP)) {
            List<Integer> out = new ArrayList<>(depCapacity);
            for (int i = 0; i < depCapacity; i++) out.add(depStart + i);
            return out;
        }
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(n)) {
                List<Integer> out = new ArrayList<>(count[i]);
                for (int k = 0; k < count[i]; k++) out.add(start[i] + k);
                return out;
            }
        }
        return List.of();
    }

    /**
     * 单个槽名 → 索引。支持数组式 {@code dep<i>}（如 dep0/dep1）；固定槽组名仅在组数量=1 时
     * 返回该组唯一索引，组数量>1 时返回 {@code null}（应经 {@link #group} 取组）。
     * 未知/越界返回 {@code null}。
     */
    public Integer singleIndex(String name) {
        String n = normalize(name);
        if (n.startsWith(DEP)) {
            String num = n.substring(DEP.length());
            try {
                int i = Integer.parseInt(num);
                if (i >= 0 && i < depCapacity) return depStart + i;
            } catch (NumberFormatException ignored) {
                // 非数字 → 落到 null
            }
            return null;
        }
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(n)) return count[i] == 1 ? start[i] : null;
        }
        return null;
    }

    /** 依赖库容器区起始索引。 */
    public int depStart() { return depStart; }

    /** 依赖库容器区槽数（容量）。 */
    public int depCapacity() { return depCapacity; }

    /** 依赖库输出与依赖库容器区之间被跳过的机器专属槽数（脚本不可见）。 */
    public int gapCount() { return gapCount; }

    /** 该槽组当前槽数（未知组名返回 0）。 */
    public int groupSize(String groupName) {
        String n = normalize(groupName);
        if (n.equals(DEP)) return depCapacity;
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(n)) return count[i];
        }
        return 0;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
