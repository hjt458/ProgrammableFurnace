# 可编程熔炉脚本语言参考

> 本文是玩家与作者共用的语言说明，**以当前代码为准**。
> 素材来源：`C:\Users\何俊涛\Doubao\chats\2026-10-19\new-chat\编译语言内容规则_草稿.md`（652 行设计草稿）。
> 草稿里的"过程修复日志"（2026-10-20 ~ 10-25 的逐条实机修复记录）已剔除，只保留**仍在生效的规则**；草稿与实现不一致的地方集中在 §11。
>
> 状态标记：**【已实现】**= 代码里有且可用；**【部分】**= 语法能过、行为有限；**【未实现】**= 只有语法或只有设定；**【已废弃】**= 草稿有、现在没了。

---

## 1. 设计原则

1. **贴近 C++**：熟悉 C++ 的人可直接迁移。凡本文没写的语法细节，按 C++ 直觉写。
2. **复用 Minecraft，不自造**：容器 = `Container`、物品 = `ItemStack`、标签 = `TagKey` + `data/<ns>/tags/items/*.json`、方向 = `Direction`。
3. **数据驱动**：能调的进 JSON（`gui_config.json` / `furnace_config.json` / `fuel_config.json`），不进代码。
4. **开放标签**：判定"能不能进槽/能不能烧"一律靠标签与配置，新物品/新物态不需要改语言。

## 2. 一个完整的程序

随包示例 `src/main/resources/programmablefurnace/scripts/furnace_smelting.mc`（进游戏拿到"示例程序"物品即可看到全文），骨架：

```cpp
MODULE("冶炼")

void refillFuel() {
    if (furnace.fuel[0].getCount() >= 64) return;
    for (auto 槽 : get("chest").slots) {
        if (!槽.isEmpty() && 槽.getItem().hasTag(tag::fuels)) {
            int 空间 = 64 - furnace.fuel[0].getCount();
            if (空间 > 0) furnace.fuel[0].add(槽.take(空间));
            if (furnace.fuel[0].getCount() >= 64) break;
        }
    }
}

void RunLoop(int tick) {
    if (tick % 20 == 0) refillFuel();              // 补料一秒一次就够
    if (!furnace.burning && ...) furnace.ignite(); // 燃烧与推进必须每 tick
    for (int i = 0; i < furnace.inputCount; i = i + 1) smelt(i);
}
```

## 3. 文件与模块

| 规则 | 状态 |
| --- | --- |
| 第一行声明 `MODULE("名字")`；缺失按 M01 判编译失败 | 【已实现】 |
| 含 `RunLoop` → **可执行文件**；不含 → **依赖库文件** | 【已实现】 |
| 编译产物只有名字，不带类型后缀 | 【已实现】 |
| 可执行文件才需要入口；依赖库文件不能执行 | 【已实现】 |

文件五态（`ContentState`）：空白 `BLANK`、可执行 `EXECUTABLE`、编译失败 `FAILED`、依赖库 `DEPENDENCY`、只读可执行 `READONLY`（示例程序/配方产物，可运行、不可重编译）。

- 空白 = 文本只含空白字符；**只有注释**的文件会因为缺 `MODULE` 判 FAILED。
- 编译失败的文件不能放进依赖库（M 类规则），也不能执行。

## 4. 词法

- 注释：`//` 单行、`/* */` 多行（未闭合报 `L06`）。
- 标识符：字母、数字、下划线，**允许中文**（`槽`、`总量`、`料`）。类型名与方法名一律英文。
- 字面量：整数（`123`）、浮点（`1.5`）、字符串（`"minecraft:coal"`）、`true` / `false`。
- **没有字符字面量**：`'a'` 不是合法写法（`char` 目前等价于单字符串，默认值 `""`）。
- 关键字（`Lexer.KEYWORDS`）：
  - 控制流：`if else switch case default for while do return break continue`
  - 模块：`MODULE import from using namespace`
  - 基础类型：`int float double bool char void string`
  - 进阶类型：`vector map pair tuple auto ItemStream Container Item ItemType`
  - 字面量：`true false`；入口：`RunLoop`

## 5. 类型

### 5.1 标量【已实现】

| 类型 | 运行期表示 | 默认值 |
| --- | --- | --- |
| `int` | 64 位整数 | `0` |
| `float` / `double` | 浮点 | `0.0` |
| `bool` | 布尔 | `false` |
| `string` | 字符串 | `""` |
| `char` | 字符串（无字符字面量） | `""` |
| `void` | 无值 | — |

运算：`+ - * / %`、`< > <= >= == !=`、`&& || !`、赋值 `=`（**没有 `+=` 等复合赋值**）。`+` 任一侧是字符串即拼接。

### 5.2 容器与游戏类型

| 类型 | 状态 | 说明 |
| --- | --- | --- |
| `vector<T>` | 【已实现】 | 运行期就是列表；可下标读/写、可被范围 `for` 遍历、`.size()` |
| `map<K,V>` | 【已实现】 | `map()` 造空表（声明不初始化也给空表）；`m[k]` 读、`m[k] = v` 写、`m.size()` / `m.has(k)` / `m.get(k)` / `m.put(k,v)` / `m.remove(k)` / `m.keys()`；读不存在的键是**运行期错误**（先用 `has` 判） |
| `pair<A,B>` | 【已实现】 | `pair(a, b)`；字段 `first` / `second` 可读可写 |
| `tuple<...>` | 【已实现】 | `tuple(a, b, ...)`；`t[i]` 按下标读/写，`t.size()`，越界是运行期错误 |
| `Container` | 【已实现】 | 箱子/木桶等：`getItems()` / `setItems(v)` / `slots` / `setSlots(v)` / `slotSize()` / `isEmpty()` |
| `Slot` | 【已实现】 | 有运行期对象（槽视图），**可当类型写**：`Slot 槽 = furnace.input[0];`（`Slot` 不是关键字，靠"类型名 + 变量名"两个标识符连着出现来识别） |
| `Item` | 【已实现】 | 物品堆叠：`getCount()` / `isEmpty()` / `getMaxStackSize()` / `is("id")` / `hasTag(tag::x)`；构造用 `Item()`（空）或 `Item("minecraft:coal")`（一个） |
| `ItemStream` | 【已实现】 | 三段式状态机：`ItemStream([容量])` → `init(输入, 输出[, 标签])` → `advance()`；`hasNext()/size()/capacity()/isEmpty()/clear()/setTag()` |
| `ItemType` | 【已实现】 | `ItemType("minecraft:iron_ore")` 造类型值；`is("id")` / `isEmpty()` / `getId()`；也能直接传给 `Slot.canPut(...)` |
| `auto` | 【已实现】 | 由初始化表达式推断；声明时必须同时初始化 |

**不做的**：指针（草稿只想要 `unique_ptr`，实际未实现）、类/结构体/继承、用户自定义泛型。

### 5.3 变量与作用域

```cpp
int 数量 = 3;          // 模块级：跨 tick 保留
void f() { bool 已取 = false; { int 临时 = 1; } }  // 函数级 / 块级
```

- 不写初始化取类型默认值。
- 同一作用域重名 → `T05`。
- 模块级变量**跨 tick 保留**（`Executor` 的 globals），函数内变量每次调用重建。

## 6. 语句与控制流【已实现】

`if / else`、`switch / case / default`（可穿透，用 `break` 跳出）、`while`、`do ... while`、`for (初始化; 条件; 步进)`、范围 `for (auto x : 容器)`、`break`、`continue`、`return`。

- `break` 只能出现在循环或 `switch` 内，`continue` 只能在循环内，否则 `T07`。
- 函数体内的 `break` 不会借用外层循环（进函数体时计数清零）。

## 7. 函数

```cpp
int 加(int a, int b) { return a + b; }   // 普通函数
void RunLoop(int tick) { ... }           // 执行入口
```

- 形参必须带类型；返回值用 `return`。
- **重载**：同名不同参数可以登记并各自 mangle（`T04` 只在"同名同参"时报）。但**运行期按裸名找函数**，同名函数只留最后一个 —— 所以**实际上不要重载**，换个名字。
- **入口 `RunLoop`**：有两种，按形参个数区分，同一个文件可以各写一个。
  - **常驻入口 `RunLoop(int tick)`**【已实现】：每 game tick 跑一次。
    - 必须返回 `void`，返回其它类型报 `M06` 并判编译失败；
    - **第一个 `int` 形参固定填当前游戏刻**（世界时间 `level.getGameTime()`，20 刻≈1 秒），用于 `tick % 20 == 0` 这类节流；
    - 其余形参取类型默认值。
  - **事件入口 `RunLoop()`**【已实现】：只在方块收到**红石上升沿**时跑一次（无供电 → 有供电那一 tick），持续供电不会每 tick 触发。
    - 同样必须返回 `void`；
    - 它与"运行中"开关无关：机器停着也能被红石触发；
    - 写它的典型理由：省掉每 tick 轮询，比如"来红石信号就整理一次输入"。
  - 两个入口共享模块级变量（`Executor` 的 globals），所以常驻入口积累的状态，事件入口读得到。

## 8. 命名空间与 using

```cpp
namespace 炉 { int 温度 = 0; void 加热() { 温度 = 温度 + 1; } }
using 炉::加热;          // 精准引入
```

- `namespace` 只做分区防重名，可嵌套。
- `using 命名空间::成员;` 【已实现】。
- `using 新名 = 类型;` 【已实现】可作变量、形参、返回值的类型；编译器展开到基底类型后照常校验（`using 整数 = int; 整数 x = "abc";` 照样报 `T01`）。
- `using namespace x;` 不采用（命名空间由 `import` 引入）。
- **没有 `std::`**：内置类型不带命名空间前缀。

## 9. 模块导入【已实现】

```cpp
import 冶炼辅助;                    // 整个模块，用时带前缀：冶炼辅助::补料()
from 冶炼辅助 import 补料;          // 精确导入，直接 补料()
from 冶炼辅助 import 工具;          // 导入模块内的命名空间
```

- **模块 = 依赖库里的一个代码文件**，模块名就是它第一行的 `MODULE("冶炼辅助")`。
- 执行时把依赖库里所有可运行/依赖库文件一起编译，按 `MODULE` 名互相链接：`from 模块 import 符号` 之后可以直接按裸名调用；`import 模块` 之后用 `模块::符号`。
- 链接期错误会真的冒出来：`M03` 模块不存在、`M04` 导入的符号不存在、`M05` 循环导入；**没被用到的 import 不校验也不链接**（按最小单位登记）。
- 入口只在**含 `RunLoop` 的那个文件**里；其余文件是纯依赖库。函数重名时入口文件自己的定义优先。

## 10. 运行期 API

### 10.1 全局

| 名字 | 含义 |
| --- | --- |
| `self` | 当前机器（= 熔炉自身，`Container`） |
| `furnace` | 当前机器的别名（草稿 F7.1 沿用），槽组从这里取 |
| `tag::名字` | 标签名，等价于字符串 `"名字"` |
| `Item()` / `Item("minecraft:coal")` | 空物品（清空槽用） / 一个该物品 |
| `ItemType("minecraft:iron_ore")` | 物品类型值（可与 `Slot.canPut` 配合） |
| `ItemStream()` | 新的物品流对象 |

### 10.2 `furnace` 的槽组与原语【已实现】

| 写法 | 说明 |
| --- | --- |
| `furnace.input[i]` | 输入槽（1~4 个，按 GUI 模式；`furnace.inputCount` 给数量） |
| `furnace.fuel[0]` | 燃料槽（1 个） |
| `furnace.output[i]` | 输出槽（与输入同数量） |
| `furnace.dep_input` / `furnace.dep_output` | 依赖库页的输入/输出槽（各 1 个） |
| `furnace.dep[i]` | 依赖库容器第 i 格（0 ~ 容量-1，`furnace_config.json` 的 `capacity`） |
| `furnace.program` | **空槽组**（程序槽已移除，遍历得到空） |
| `furnace.burning` | 是否在燃烧（读） |
| `furnace.burnTime` | 剩余燃时（读） |
| `furnace.ignite()` | 消耗一个燃料并点燃；不可燃返回 `false` |
| `furnace.advance(i)` | 推进输入槽 i 的进度（内含燃烧判定）；返回"是否到达上限" |
| `furnace.result(item)` | 查烧炼配方的产物（无配方返回空 `Item()`） |
| `furnace.getProgress(i)` / `furnace.setProgress(i, p)` | 读写进度 |

槽的"能不能放"由容器层决定：输出槽、依赖库容器区一律拒收；依赖库输入槽只收代码文件；拓展槽只收扩容器/优化器。**漏斗和界面同一条规则**。

### 10.3 `Slot`（槽视图）与 `Item`

| `Slot` 方法 | 语义 |
| --- | --- |
| `getItem()` / `getCount()` / `isEmpty()` | 读 |
| `setItem(item)` | **严格放置**：槽空才成功；`Item()` 清空；槽非空返回 `false` |
| `add(item)` | **合并放置**：同物品未满则堆叠，放不下返回 `false`（可能部分放入，需自己判空） |
| `take(n)` | 拆出 n 个并返回（修改源槽） |
| `canPut(item \| ItemType \| "id")` | 这个槽能不能收它——走容器层的 `canPlaceItem`，**与漏斗同一条规则**（所以 `输出槽.canPut(x)` 恒为 `false`） |

### 10.4 定位与搜索

| 调用 | 说明 |
| --- | --- |
| `get("chest")` | 半径 32 内**最近的**同名容器（匹配方块 id 名与方块标签名）；结果缓存 20 tick |
| `getContainer(pos)` / `getContainer(x, y, z)` | 按坐标取容器 |
| `searchByTag(基准, tag::x, 范围)` | 按标签搜容器 |
| `searchByName("箱子", 范围)` | 按名字搜容器 |
| `radius(pos, r)` / `region(pos1, pos2)` | 范围构造（球/盒） |

- 空结果返回**空列表**，不报错（`get` 未命中也返回空列表，访问其成员会得到"未实现"错误——要先判空）。
- **没有坐标字面量**：坐标只能来自 `self`、`radius`/`region` 的结果，或 `getContainer(x,y,z)` 直接给三个整数。

### 10.5 物品流 `ItemStream`【已实现】

```cpp
ItemStream 流 = ItemStream();          // 默认缓存 64 件
ItemStream 小流 = ItemStream(16);      // 自定义缓存容量
流.init({furnace.dep_input}, {箱子}, tag::粗铁);  // 输入端 / 输出端 / 可选筛选标签
流.advance();                          // 推进一步：先往输出送，再从输入补进缓存
while (流.hasNext()) { 流.advance(); } // hasNext = 缓存有货或输入还有能收的
int 待发 = 流.size();                  // 缓存里现有件数
```

- 三段式状态机（草稿 F7.1）：**输入端 → 内部缓存（容量固定）→ 输出端**。
- `advance()` 每次最多推一步：先尽量把缓存送进输出（同物品会叠、尊重 `canPlaceItem`），再从输入搬**一件**进缓存；真的移动了物品才返回 `true`。返回 `false` 说明"下游满了或上游空了"，脚本应退避而不是死循环。
- 缓存容量默认 64 件，`ItemStream(n)` 可改；`size()` / `capacity()` / `isEmpty()` / `clear()` / `setTag(tag::x)` 可查可控。
- 缓存里的物品**只在这个流对象里**，机器重启即丢——跨重启要落盘请用槽位。

### 10.6 复合值（`map` / `pair` / `tuple`）【已实现】

```cpp
map<string,int> 计数;            // 声明即空表
计数["煤炭"] = 1;
计数["煤炭"] = 计数["煤炭"] + 1;
if (计数.has("煤炭")) {       // 读不存在的键会报运行期错误，先判
    int n = 计数["煤炭"];
}

pair<int,string> 一对 = pair(3, "铁");
int 数量 = 一对.first;
一对.second = "铜";             // 字段可写

tuple<int,int,int> 三元 = tuple(1, 2, 3);
int 中 = 三元[1];                // 定长，按整数下标存取
```

这几个是**语言自带的值**（解释器直接构造，不经过 `Env`、不占函数调用预算），所以不依赖机器：
`map()`、`pair(a,b)`、`tuple(a,b,...)` 三处构造，其余都是字段/下标/方法。
`map` 保持插入顺序（便于调试与 `keys()` 遍历），键用 `==` 语义比较。

## 11. 草稿 → 现状的差异（重要）

| # | 草稿说法 | 现状 |
| --- | --- | --- |
| 1 | 有程序槽 `furnace.program`，程序放这里执行 | 【已废弃】程序槽整槽移除；程序文件从**依赖库页的输入槽**导入到依赖库容器，执行器取容器里第一个可运行文件 |
| 2 | `setGui("212")` 原语切换界面 | 【已废弃】由**U 盘扩展卡**（I/II/III 放拓展槽）决定 212/313/414，没卡就是 111 |
| 3 | 燃料判定 `hasTag(tag::fuels)` | 【已改】26.3 的 `minecraft:fuels` **不含煤炭**；燃料与燃时按 `fuel_config.json` + 物品自身的 `COOKING_FUEL` 组件，脚本里用 `is("minecraft:coal")` 这类 id 判断 |
| 4 | 槽组用 `dep(i)` 取依赖库第 i 格 | 写成 `furnace.dep[i]` |
| 5 | 例子里 `for (Slot 槽 : ...)` | 【已实现】`Slot` 仍不是关键字，但"类型名 + 变量名"两标识符判据让它能写：`Slot 槽 = furnace.input[0];` 与 `for (auto 槽 : ...)` 都行 |
| 6 | 文件类型/入口靠 `RunLoop`，红石版入口 | 【已实现】两种入口按形参区分：`RunLoop(int tick)` 每 tick、`RunLoop()` 红石上升沿触发一次 |
| 7 | `import` 链接期校验保证跨文件可用 | 【已实现】依赖库里的文件按 `MODULE` 名互相链接，`M03`/`M04`/`M05` 真的会报 |
| 8 | `ItemStream` 有缓存与状态机 | 【已实现】输入端 → 内部缓存（容量可传）→ 输出端，`advance()` 每次推一步 |
| 9 | 物态/标签另造 JSON | 直接复用 MC 标签系统 |
| 10 | 错误定位"文件名:行号" | 位置给到 `行:列`，但文件名是占位的 `<classify>`（编译的是物品里的字符串，没有真实文件名） |
| 11 | 槽位数量 input=1/fuel=1/output=1 | 现在固定最大布局 4/1/4，按 GUI 模式启用前 N 个 |
| 12 | 指针只留 `unique_ptr` | 未实现（也没有裸指针），语言里没有指针概念 |

## 12. 错误码

编译期错误码分五类，消息形如 `S03 缺少分号 @ <classify>:4:8`。

| 码 | 含义 | 是否真有产出点 |
| --- | --- | --- |
| L01 | 非法字符（控制字符） | ✅ |
| L02 | 字符串未闭合 | ✅ |
| L04 | 数字格式错误（`12abc`、`1.2.3`） | ✅ |
| L05 | 未知符号（`$`、`@` 等） | ✅ |
| L06 | 块注释未闭合 | ✅ |
| S01 | 语句结构错误 | ✅ |
| S02 | 括号不匹配 | ✅ |
| S03 | 缺少分号 | ✅ |
| S04 | 缺少大括号 | ✅ |
| S05 | 表达式非法 | ✅ |
| T01 | 类型不匹配 | ✅ |
| T02 | 未声明变量（编译期，按名字去重） | ✅（2026-11-17 启用；运行期仍有同义兜底） |
| T03 | 把变量当函数调用 | ✅ |
| T04 | 重载冲突（同名同参） | ✅ |
| T05 | 变量重定义 | ✅ |
| T06 | 返回类型不符 | ✅ |
| T07 | 作用域越界（循环外的 break/continue） | ✅ |
| M01 | 缺 `MODULE` | ✅ |
| M02 | `MODULE` 重复 | ✅ |
| M03 | import 找不到模块 | 代码有、未接入 |
| M04 | 导入符号不存在 | 代码有、未接入 |
| M05 | 循环导入 | 代码有、未接入 |
| M06 | 入口签名不对（`RunLoop` 返回值非 void） | ✅ |

**运行期错误**（不判编译失败）：未声明变量、未实现的内置调用、类型运算错、超出预算。

> `L03_ILLEGAL_IDENTIFIER` 与 `O01_OPTIMIZER_FAILURE` 已于 2026-11-17 从码表删除：两者从来没有产出点，输入也构造不出来。**错误的码表比没有码表更糟**——将来加码先想清楚"哪条规则、哪段代码产出它"。

## 13. 运行期保护（防止脚本挂掉服务器）

| 限制 | 值 | 触发后果 |
| --- | --- | --- |
| 单 tick 步数 | 200 000 | 本次执行中止，报"超过单 tick 执行预算" |
| 函数调用嵌套 | 64 层 | 中止，报"调用嵌套过深" |
| 编译期表达式嵌套 | 128 层 | 判编译失败，报"表达式嵌套过深" |
| 单次提交代码长度 | 16 384 字符 | 包被丢弃（客户端根本发不出来） |

这些是硬上限，**不要试图用长循环或深递归绕过**——它们就是用来兜住 `while(true){}` 的。

## 14. 已知限制（明确不做或还没做）

1. 没有指针、类、继承、模板；没有用户自定义泛型之外的抽象。
2. 没有 `+=` 之类复合赋值、没有三元 `?:`、没有 `for` 的省略写法（`for(;;)`）。
3. `map` / `pair` / `tuple` 没有嵌套泛型的类型校验（`map<string,vector<int>>` 能写，但元素类型不做检查）。
4. 没有坐标字面量与方向（`Direction`）暴露；六面输入输出未实现。