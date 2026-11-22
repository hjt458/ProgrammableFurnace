// 可编程烧炼脚本（方向 B + 配方查询 + 多输入并行）：从旁边箱子取粗铁/煤 → 每输入槽独立烧炼 → 铁锭放回箱子（堆叠合并）
// 放进熔炉程序槽（需 EXECUTABLE）。语言 API 用英文；变量名可中文。
// 结构：业务拆成模块级 helper（refillFuel/refillInput/hasInput/smelt/returnItem），RunLoop 只编排动作；break/return 免 bool 标志。
// 槽 API：setItem=严格设置（槽非空失败）、add=合并放入（同物品堆叠）、take=拆出。
MODULE("冶炼")
// 补燃料：fuel 未满 → 从箱子补煤到满或箱子空（多输入共享燃时，全局一次）
void refillFuel() {
    if (furnace.fuel[0].getCount() >= 64) return;
    for (auto 槽 : get("chest").slots) {
        if (!槽.isEmpty()
            && (槽.getItem().is("minecraft:coal") || 槽.getItem().is("minecraft:charcoal") || 槽.getItem().hasTag(tag::fuels))) {
            int 空间 = 64 - furnace.fuel[0].getCount();
            if (空间 > 0) furnace.fuel[0].add(槽.take(空间));
            if (furnace.fuel[0].getCount() >= 64) break;
        }
    }
}
// 补粗铁到输入槽 i：未满 → 从箱子补到满或箱子空
void refillInput(int i) {
    if (furnace.input[i].getCount() >= 64) return;
    for (auto 槽 : get("chest").slots) {
        if (!槽.isEmpty() && 槽.getItem().is("minecraft:raw_iron")) {
            int 空间 = 64 - furnace.input[i].getCount();
            if (空间 > 0) furnace.input[i].add(槽.take(空间));
            if (furnace.input[i].getCount() >= 64) break;
        }
    }
}
// 自动分拣：把 4 个输入槽的物品均匀分配到前 N 个激活槽（每格都有、尽量平均；add 失败放回源槽）
void sortInput(int N) {
    if (N <= 1) return;
    int 总量 = 0;
    for (int s = 0; s < 4; s = s + 1)
        if (!furnace.input[s].getItem().isEmpty()) 总量 = 总量 + furnace.input[s].getCount();
    if (总量 <= 0) return;
    int 基准 = 总量 / N;
    if (基准 > 64) 基准 = 64;
    int 余 = 总量 - 基准 * N;
    // 阶段1：把超目标格的多余搬到不足格（同种才成功）
    for (int j = 0; j < N; j = j + 1) {
        int 目标 = 基准;
        if (j < 余 && 目标 < 64) 目标 = 目标 + 1;
        int 超 = furnace.input[j].getCount() - 目标;
        if (超 <= 0) continue;
        for (int t = 0; t < N; t = t + 1) {
            if (t == j) continue;
            int 目标2 = 基准;
            if (t < 余 && 目标2 < 64) 目标2 = 目标2 + 1;
            int 缺2 = 目标2 - furnace.input[t].getCount();
            if (缺2 <= 0) continue;
            int 移 = 超;
            if (缺2 < 移) 移 = 缺2;
            Item 料 = furnace.input[j].take(移);
            if (!furnace.input[t].add(料)) furnace.input[j].add(料);
            超 = furnace.input[j].getCount() - 目标;
            if (超 <= 0) break;
        }
    }
    // 阶段2：不足格从其他格（含后槽）补
    for (int j = 0; j < N; j = j + 1) {
        int 目标 = 基准;
        if (j < 余 && 目标 < 64) 目标 = 目标 + 1;
        int 缺 = 目标 - furnace.input[j].getCount();
        if (缺 <= 0) continue;
        for (int s = 3; s >= 0; s = s - 1) {
            if (s == j) continue;
            if (furnace.input[s].getItem().isEmpty()) continue;
            int 取 = furnace.input[s].take(缺);
            if (!furnace.input[j].add(取)) furnace.input[s].add(取);
            缺 = 目标 - furnace.input[j].getCount();
            if (缺 <= 0) break;
        }
    }
}
// 任一输入槽有料？
bool hasInput() {
    for (int i = 0; i < furnace.inputCount; i = i + 1)
        if (!furnace.input[i].getItem().isEmpty()) return true;
    return false;
}
// 烧炼输入槽 i：燃烧中经 furnace.advance 推进进度（封装燃烧+进度+上限）；达到上限 → 配方产出入输出槽、消耗 input 1 个
void smelt(int i) {
    if (furnace.input[i].getItem().isEmpty()) return;
    if (!furnace.advance(i)) return;   // 未燃烧 / 未满 → 本次不产出
    furnace.output[i].add(furnace.result(furnace.input[i].getItem()));
    furnace.input[i].take(1);
    furnace.setProgress(i, 0);
}
// 输出槽 i 铁锭放回箱子：优先合并已有铁锭槽，否则空槽；放成即返回
void returnItem(int i) {
    if (furnace.output[i].getItem().isEmpty()) return;
    for (auto 槽 : get("chest").slots) {
        if (!槽.isEmpty() && 槽.getItem().is("minecraft:iron_ingot")) {
            if (槽.add(furnace.output[i].getItem())) { furnace.output[i].setItem(Item()); return; }
        }
    }
    for (auto 槽 : get("chest").slots) {
        if (槽.isEmpty()) {
            if (槽.add(furnace.output[i].getItem())) { furnace.output[i].setItem(Item()); return; }
        }
    }
}
void RunLoop(int tick) {
    // tick 是当前游戏刻（20 刻约 1 秒）。补料/整理一秒一次就够，燃烧与推进必须每 tick 都走。
    if (tick % 20 == 0) {
        refillFuel();
        for (int i = 0; i < furnace.inputCount; i = i + 1) refillInput(i);
        sortInput(furnace.inputCount);
    }
    if (!furnace.burning && hasInput() && !furnace.fuel[0].getItem().isEmpty()) furnace.ignite();
    for (int i = 0; i < furnace.inputCount; i = i + 1) smelt(i);
    for (int i = 0; i < furnace.inputCount; i = i + 1) returnItem(i);
}
