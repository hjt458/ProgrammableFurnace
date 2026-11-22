package dev.programmablefurnace.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * U盘扩展卡：放入执行器功能槽，决定工作界面多输入/输出布局。
 * <p>三档等级（图标颜色蓝→紫→橙，对应网游道具分级）：
 * tier 1 激活 212 布局（2 输入/2 输出）、tier 2 激活 313（3 输入/3 输出）、tier 3 激活 414（4 输入/4 输出）。
 * 功能槽为空（或放入非本卡物品）时恢复 111 基础布局。</p>
 */
public class UsbCardItem extends Item {

    /**
     * U盘扩展卡共用的 item tag：同一 tag 的拓展卡在功能槽内视为同类扩展，
     * 布局推导时只取其中等级最高的一张生效（见 {@code ProgrammableFurnaceBlockEntity#guiModeFromExtras}）。
     */
    public static final TagKey<Item> USB_EXPANSION_TAG =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("programmablefurnace", "usb_expansion"));

    /** 等级（1/2/3），图标颜色蓝→紫→橙。 */
    private final int tier;
    /** 本卡激活的 GUI 布局编码（212/313/414）。 */
    private final int guiMode;

    public UsbCardItem(Properties properties, int tier, int guiMode) {
        super(properties);
        this.tier = tier;
        this.guiMode = guiMode;
    }

    /** 等级（1/2/3），图标颜色蓝→紫→橙。 */
    public int tier() {
        return tier;
    }

    /** 本卡激活的 GUI 布局编码（212/313/414）。 */
    public int guiMode() {
        return guiMode;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.programmablefurnace.usb_card.tier" + tier);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> consumer, TooltipFlag tooltipFlag) {
        consumer.accept(Component.translatable("item.programmablefurnace.usb_card.hint" + tier));
        consumer.accept(Component.translatable("item.programmablefurnace.usb_card.desc" + tier));
    }
}
