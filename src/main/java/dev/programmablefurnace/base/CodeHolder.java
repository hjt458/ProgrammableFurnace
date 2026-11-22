package dev.programmablefurnace.base;

import net.minecraft.world.item.ItemStack;

/**
 * 可携带代码的物品（编码文件、编译磁盘）实现此接口。
 * 读写走 {@link CodeStorage}，默认即 NBT 绑定（RAII）。
 */
public interface CodeHolder {

    default boolean hasCode(ItemStack stack) {
        return CodeStorage.hasCode(stack);
    }

    default String getCode(ItemStack stack) {
        return CodeStorage.getCode(stack);
    }

    default void setCode(ItemStack stack, String code) {
        CodeStorage.setCode(stack, code);
    }

    default FileType getFileType(ItemStack stack) {
        return CodeStorage.getFileType(stack);
    }

    default void setFileType(ItemStack stack, FileType type) {
        CodeStorage.setFileType(stack, type);
    }

    default ContentState getContentState(ItemStack stack) {
        return CodeStorage.getContentState(stack);
    }

    default void setContentState(ItemStack stack, ContentState state) {
        CodeStorage.setContentState(stack, state);
    }
}
