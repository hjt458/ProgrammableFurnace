package dev.programmablefurnace.machine;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 机器注册工厂（工厂模式）：以 {@link MachineSpec} 为单位统一注册一个双界面机器的
 * Block + BlockItem + BlockEntityType + MenuType，并登记创造标签顺序。
 * <p>纯 common（不引用 client 类），Screen 的 client 绑定由 {@code ProgrammableFurnaceClient} 单独按 id 处理。
 * 后续新增机器：构造一个 {@link MachineSpec}（id / 方块属性 / be 工厂 / menu 工厂 / 创造标签顺序），
 * 调用 {@link #register} 即完成服务端全套注册；创造标签自动生效。</p>
 */
public final class MachineRegistry {

    private MachineRegistry() {}

    private static final List<MachineHandle<?, ?, ?>> MACHINES = new ArrayList<>();

    /** 单个已注册机器的句柄。 */
    public record MachineHandle<B extends MachineBlock, BE extends MachineBlockEntity, M extends AbstractContainerMenu>(
            String id,
            DeferredBlock<B> block,
            DeferredItem<BlockItem> item,
            Supplier<BlockEntityType<BE>> blockEntity,
            Supplier<MenuType<M>> menu,
            int creativeTabOrder
    ) {}

    /** 机器定义：工厂据此注册整套服务端机器。 */
    public record MachineSpec<B extends MachineBlock, BE extends MachineBlockEntity, M extends AbstractContainerMenu>(
            String id,
            BlockBehaviour.Properties blockProperties,
            Function<BlockBehaviour.Properties, B> blockFactory,
            BlockEntityType.BlockEntitySupplier<BE> blockEntityFactory,
            Supplier<MenuType<M>> menuFactory,
            int creativeTabOrder
    ) {}

    /** 注册一个机器（block + item + be + menu + 创造标签顺序）。注册表由调用方传入（通常是各机器自己的 {@code *Registration}）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <B extends MachineBlock, BE extends MachineBlockEntity, M extends AbstractContainerMenu>
    MachineHandle<B, BE, M> register(MachineSpec<B, BE, M> spec,
                                     DeferredRegister.Blocks blocks,
                                     DeferredRegister.Items items,
                                     DeferredRegister<BlockEntityType<?>> blockEntities,
                                     DeferredRegister<MenuType<?>> menus) {
        DeferredBlock<B> block = blocks.registerBlock(spec.id(), spec.blockFactory(), () -> spec.blockProperties());
        DeferredItem<BlockItem> item = items.registerSimpleBlockItem(spec.id(), block);
        Supplier<BlockEntityType<BE>> be = blockEntities.register(spec.id(),
                () -> new BlockEntityType<>((BlockEntityType.BlockEntitySupplier) spec.blockEntityFactory(), false, block.get()));
        Supplier<MenuType<M>> menu = menus.register(spec.id(), (Supplier) spec.menuFactory());
        MachineHandle<B, BE, M> handle = new MachineHandle<>(spec.id(), block, item, be, menu, spec.creativeTabOrder());
        MACHINES.add(handle);
        return handle;
    }

    /** 全部已注册机器（只读快照；调用方拿不到内部列表，避免"注册表被外部改空"这类问题）。 */
    public static List<MachineHandle<?, ?, ?>> machines() {
        return List.copyOf(MACHINES);
    }

    /**
     * 按 id 找机器。{@code ProgrammableFurnaceClient} 用它把 registry 里的每个 id 映射到界面，
     * 找不到映射时应当在启动期就报错，而不是让玩家对着一个打不开的方块。
     */
    public static Optional<MachineHandle<?, ?, ?>> find(String id) {
        return MACHINES.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** 按创造标签顺序排序的机器（用于创造标签展示）。 */
    public static List<MachineHandle<?, ?, ?>> machinesByCreativeOrder() {
        return MACHINES.stream().sorted(Comparator.comparingInt(MachineHandle::creativeTabOrder)).toList();
    }
}
