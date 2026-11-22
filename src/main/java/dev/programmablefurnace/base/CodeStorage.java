package dev.programmablefurnace.base;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * 代码存储工具：代码直接绑定在物品的 NBT（CustomData）上——
 * 这是最天然的 RAII：物品消失，代码随之消失，无泄漏、可随存档持久化。
 * 若将来改为"全局代码注册表 + UUID 引用"，只需替换本类的读写实现。
 */
public final class CodeStorage {

    public static final String CODE_TAG = "code";
    public static final String SOURCE_NAME_TAG = "source_name";
    public static final String FILE_TYPE_TAG = "file_type";
    public static final String CONTENT_STATE_TAG = "content_state";
    /** 编译失败时随物品携带的错误文本（按 {@link #COMPILE_ERROR_SEPARATOR} 分隔），供 tooltip 与日志读取。 */
    public static final String COMPILE_ERRORS_TAG = "compile_errors";
    /** 错误之间的分隔符：换行在 NBT 字符串里安全，且与 tooltip 的逐行渲染一致。 */
    private static final String COMPILE_ERROR_SEPARATOR = "\n";

    private CodeStorage() {}

    public static boolean hasCode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return copyTag(stack).contains(CODE_TAG);
    }

    /** 读取物品上携带的代码，没有则返回空串。 */
    public static String getCode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return copyTag(stack).getString(CODE_TAG).orElse("");
    }

    /** 把代码写入物品 NBT（保留同 Tag 上的其他字段）。图标由独立物品各自的模型决定，不在此动态切换。 */
    public static void setCode(ItemStack stack, String code) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putString(CODE_TAG, code == null ? "" : code));
    }

    public static void clearCode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(CODE_TAG));
    }

    /** 记录来源编码文件的名字（编译产物"同名物品"用它显示）。 */
    public static void setSourceName(ItemStack stack, String name) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putString(SOURCE_NAME_TAG, name == null ? "" : name));
    }

    public static String getSourceName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return copyTag(stack).getString(SOURCE_NAME_TAG).orElse("");
    }

    /** 读取文件类型，未设置时默认 RUNTIME（运行库）。 */
    public static FileType getFileType(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return FileType.RUNTIME;
        CompoundTag tag = copyTag(stack);
        if (!tag.contains(FILE_TYPE_TAG)) return FileType.RUNTIME;
        int ord = tag.getInt(FILE_TYPE_TAG).orElse(0);
        FileType[] vals = FileType.values();
        if (ord <= 0 || ord >= vals.length - 1) return FileType.RUNTIME; // 避开 BEGIN/END 占位
        return vals[ord];
    }

    /** 写入文件类型。 */
    public static void setFileType(ItemStack stack, FileType type) {
        if (stack == null || stack.isEmpty()) return;
        int ord = type == null ? FileType.RUNTIME.ordinal() : type.ordinal();
        int finalOrd = ord;
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putInt(FILE_TYPE_TAG, finalOrd));
    }

    /**
     * 由编译产物状态推导文件类型：可执行 → 运行库，依赖 → 依赖库，其余（空白/失败/无状态）没有类型。
     * <p>这条映射决定界面上"依赖库页签能否输出它"（{@code MachineConfigs.canOutput}）与文件归类的展示，
     * 所以只在这里放一份；返回 {@code null} 表示调用方不该写 file_type（保持"无类型"语义）。</p>
     */
    public static FileType fileTypeFor(ContentState state) {
        if (state == null) return null;
        return switch (state) {
            case EXECUTABLE -> FileType.RUNTIME;
            case DEPENDENCY -> FileType.DEPENDENCY;
            default -> null;
        };
    }

    // ===================== 内容状态（空白/可执行/编译失败）=====================
    /** 代码分析器：用词法 + 语法分析（{@link CompilerAnalyzer}）判定内容状态，替代占位实现。 */
    private static CodeAnalyzer ANALYZER = new CompilerAnalyzer();

    /** 替换代码分析器（供后续 scan/parse 接入）。 */
    public static void setAnalyzer(CodeAnalyzer analyzer) {
        if (analyzer != null) ANALYZER = analyzer;
    }

    /** 读取内容状态，未设置时按代码自动判断。 */
    public static ContentState getContentState(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ContentState.BLANK;
        CompoundTag tag = copyTag(stack);
        if (!tag.contains(CONTENT_STATE_TAG)) return classify(tag.getString(CODE_TAG).orElse(""));
        int ord = tag.getInt(CONTENT_STATE_TAG).orElse(0);
        ContentState[] vals = ContentState.values();
        if (ord < 0 || ord >= vals.length) return ContentState.BLANK;
        return vals[ord];
    }

    /** 写入内容状态，并同步设置对应物品模型（ITEM_MODEL 动态切换图标）。这是内容状态的**唯一写入口**。 */
    public static void setContentState(ItemStack stack, ContentState state) {
        if (stack == null || stack.isEmpty()) return;
        ContentState effective = state == null ? ContentState.BLANK : state;
        int finalOrd = effective.ordinal();
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putInt(CONTENT_STATE_TAG, finalOrd));
        stack.set(DataComponents.ITEM_MODEL, modelFor(effective));
    }

    /**
     * 读取"物品自称的状态"：有 tag 用 tag，没有则退回物品自身代表的固定状态（{@code fallback}）。
     * <p>物品实例是空白/可执行/失败三种固定图标之一，但同一个可执行文件物品也可能装着依赖库内容
     * （{@link ContentState#DEPENDENCY}），此时显示名与图标要以 tag 为准而不是物品实例。</p>
     */
    public static ContentState getContentStateOr(ItemStack stack, ContentState fallback) {
        if (stack == null || stack.isEmpty()) return fallback;
        CompoundTag tag = copyTag(stack);
        if (!tag.contains(CONTENT_STATE_TAG)) return fallback;
        int ord = tag.getInt(CONTENT_STATE_TAG).orElse(0);
        ContentState[] vals = ContentState.values();
        if (ord < 0 || ord >= vals.length) return fallback;
        return vals[ord];
    }

    /** 依据代码内容判定三态，委托给当前 {@link CodeAnalyzer}（默认 {@link CompilerAnalyzer}）。 */
    public static ContentState classify(String code) {
        return ANALYZER.analyze(code);
    }

    // ===================== 编译错误（批次 1：让失败可诊断）=====================

    /**
     * 把编译错误写进物品，供 tooltip 直接展示。
     * <p>只存文本：错误码与位置已在 {@code CompileError#toString()} 里格式化好
     * （{@code S02 括号不匹配 @ <classify>:1:5}），游戏端无需再理解编译期类型。</p>
     */
    public static void setCompileErrors(ItemStack stack, List<String> errors) {
        if (stack == null || stack.isEmpty()) return;
        if (errors == null || errors.isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(COMPILE_ERRORS_TAG));
            return;
        }
        String joined = String.join(COMPILE_ERROR_SEPARATOR, errors);
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putString(COMPILE_ERRORS_TAG, joined));
    }

    /** 读取物品携带的编译错误；没有则返回空列表。 */
    public static List<String> getCompileErrors(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return List.of();
        String joined = copyTag(stack).getString(COMPILE_ERRORS_TAG).orElse("");
        if (joined.isEmpty()) return List.of();
        return List.of(joined.split(COMPILE_ERROR_SEPARATOR, -1));
    }

    /**
     * 依据内容状态返回对应物品模型 ID。
     * <p>ITEM_MODEL 指向的是 {@code assets/<ns>/items/<name>.json} 里的<b>物品模型定义</b>，
     * 所以这里只写 {@code code_file_exec} 这样的路径，**不能带 {@code item/} 前缀**
     * （带了会去找 {@code items/item/code_file_exec.json}，不存在 → 图标解析失败）。</p>
     */
    private static Identifier modelFor(ContentState state) {
        return switch (state) {
            case EXECUTABLE, READONLY -> Identifier.fromNamespaceAndPath("programmablefurnace", "code_file_exec");
            case FAILED -> Identifier.fromNamespaceAndPath("programmablefurnace", "code_file_failed");
            case DEPENDENCY -> Identifier.fromNamespaceAndPath("programmablefurnace", "code_file_dependency");
            default -> Identifier.fromNamespaceAndPath("programmablefurnace", "code_file_blank");
        };
    }

    private static CompoundTag copyTag(ItemStack stack) {
        CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return cd.copyTag();
    }
}

