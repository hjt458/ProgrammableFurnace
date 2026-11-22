package dev.programmablefurnace.compiler;

import dev.programmablefurnace.machine.MachineBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 编译器方块：继承通用 {@link MachineBlock}，仅提供方块实体工厂。
 * 编译/依赖库双界面由 {@link CompilerBlockEntity} + {@link CompilerMenu} + {@link CompilerScreen} 实现。
 */
public class CompilerBlock extends MachineBlock {

    public CompilerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new CompilerBlockEntity(pos, state);
    }
}
