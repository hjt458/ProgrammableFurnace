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
 * 燃烧优化卡（U盘样式拓展）：放入执行器功能槽，点火时读取 {@code fuel_config.json} 对应 boost 档的四值，
 * 与当前燃料逐项取"更好"（燃时/增量/效率取大，进度上限取小）。
 * 三档等级（图标颜色绿→黄→红，与布局卡蓝紫橙区分）：tier 1/2/3 对应 boost 档 1/2/3。
 * 独立于布局卡 tag（{@link #USB_BOOST_TAG}），不影响工作界面布局。
 */
public class BoostCardItem extends Item {

    /** 燃烧优化卡共用 tag：同 tag 拓展只取最高档生效（见 {@code ProgrammableFurnaceBlockEntity#boostTier}）。 */
    public static final TagKey<Item> USB_BOOST_TAG =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("programmablefurnace", "usb_boost"));

    /** 优化档位（1/2/3），对应 fuel_config 的 boosts 档。 */
    private final int tier;

    public BoostCardItem(Properties properties, int tier) {
        super(properties);
        this.tier = tier;
    }

    /** 优化档位（1/2/3）。 */
    public int tier() {
        return tier;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.programmablefurnace.usb_card_boost.tier" + tier);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> consumer, TooltipFlag tooltipFlag) {
        consumer.accept(Component.translatable("item.programmablefurnace.usb_card_boost.hint" + tier));
        consumer.accept(Component.translatable("item.programmablefurnace.usb_card_boost.desc" + tier));
    }
}
