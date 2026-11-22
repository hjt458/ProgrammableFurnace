package dev.programmablefurnace.util;

import dev.programmablefurnace.base.BatchStorage;
import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.FileType;
import dev.programmablefurnace.base.GuiConfig;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 依赖库界面（ScrollView 列表）的通用渲染。编译器与执行器的依赖库界面共用，
 * 保证"样式一模一样"；布局坐标来自 {@link GuiConfig.layout().dependency}。
 * 排序/输入规则由方块实体的 {@link BatchStorage} 实现决定，本类只按 offset 渲染容器条目。
 */
public final class DependencyUi {

    private DependencyUi() {}

    /** 渲染依赖库 ScrollView 列表（容器底 + 边框 + 条目两行：文件名/类型标签）。 */
    public static void renderList(GuiGraphicsExtractor graphics, Font font,
                                  int leftPos, int topPos, BatchStorage storage,
                                  int scrollOffset, int selectedOffset) {
        int listX = GuiConfig.layout().dependency.listX;
        int listY = GuiConfig.layout().dependency.listY;
        int listW = GuiConfig.layout().dependency.listW;
        int listH = GuiConfig.layout().dependency.listH;
        int rowH = GuiConfig.layout().dependency.rowH;
        int capacity = storage.batchCapacity();
        int baseX = leftPos + listX;
        int baseY = topPos + listY;
        // ScrollView 容器底 + 边框（对调：底 9A 浅、边框 6A 深）
        graphics.fill(baseX - 1, baseY - 1, baseX + listW + 1, baseY + listH + 1, 0xFF6A6A6A);
        graphics.fill(baseX, baseY, baseX + listW, baseY + listH, 0xFF9A9A9A);
        int visibleRows = listH / rowH;
        for (int r = 0; r < visibleRows; r++) {
            int offset = scrollOffset + r;
            if (offset >= capacity) break;
            ItemStack entry = storage.getBatchItem(offset);
            boolean selected = offset == selectedOffset;
            int rowX = baseX;
            int rowY = baseY + r * rowH;
            int rowW = listW;
            int rowH_ = rowH;
            if (selected) {
                graphics.fill(rowX, rowY, rowX + rowW, rowY + rowH_, 0xFF3D6A9E);
                graphics.fill(rowX, rowY, rowX + rowW, rowY + 1, 0xFF9ECBFF);
                graphics.fill(rowX, rowY + rowH_ - 1, rowX + rowW, rowY + rowH_, 0xFF1D3A56);
            } else if (!entry.isEmpty()) {
                // 未选中但有内容：按钮样式（浅灰底 + 高光边，8A/AA/6A）
                graphics.fill(rowX, rowY, rowX + rowW, rowY + rowH_, 0xFF8A8A8A);
                graphics.fill(rowX, rowY, rowX + rowW, rowY + 1, 0xFFAAAAAA);
                graphics.fill(rowX, rowY + rowH_ - 1, rowX + rowW, rowY + rowH_, 0xFF6A6A6A);
            } else {
                // 空条目：与容器底同色（浅色 9A）
                graphics.fill(rowX, rowY, rowX + rowW, rowY + rowH_, 0xFF9A9A9A);
            }
            if (!entry.isEmpty()) {
                String fileLine = TextUtil.firstLine(CodeStorage.getCode(entry));
                if (fileLine.isEmpty()) fileLine = "§7<空>";
                FileType type = CodeStorage.getFileType(entry);
                Component typeTag = type == FileType.DEPENDENCY
                        ? Component.translatable("gui.programmablefurnace.type.dependency")
                        : Component.translatable("gui.programmablefurnace.type.runtime");
                // 类型标记贴右端对齐；文件名靠左并截断，保证不与标记重叠
                int tagWidth = font.width(typeTag);
                int tagX = rowX + rowW - 2 - tagWidth;
                int nameMaxWidth = Math.max(4, (tagX - rowX) - 6); // 文件名可用宽度（留 2px 间距）
                String namePart = TextUtil.clipWidth(fileLine, nameMaxWidth, font);
                graphics.text(font, Component.literal(namePart), rowX + 2, rowY + 2, 0xFFFFFFFF);
                graphics.text(font, typeTag, tagX, rowY + 2, 0xFFDDDDDD);
            }
        }
    }
}
