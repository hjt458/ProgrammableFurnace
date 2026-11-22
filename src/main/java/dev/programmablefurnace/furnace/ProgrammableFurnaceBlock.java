package dev.programmablefurnace.furnace;

import dev.programmablefurnace.base.IExecutor;
import dev.programmablefurnace.machine.MachineBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import org.jetbrains.annotations.Nullable;

/**
 * 可编程熔炉（执行器）方块：继承通用 {@link MachineBlock}，提供方块实体工厂 + 每 tick 执行逻辑的 ticker。
 * 实现 {@link IExecutor}（工作界面每 20 tick 执行程序槽可执行文件）。
 */
public class ProgrammableFurnaceBlock extends MachineBlock implements IExecutor {

    /** 燃烧状态：点燃时方块前脸变亮发光（lit 贴图）。 */
    public static final BooleanProperty LIT = BooleanProperty.create("lit");

    public ProgrammableFurnaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, Boolean.FALSE));
    }

    /** 放置时朝向玩家，初始不燃烧。 */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context).setValue(LIT, Boolean.FALSE);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);   // FACING（基类注册）
        builder.add(LIT);
    }

    /**
     * 执行器真实执行：委托方块实体 {@link ProgrammableFurnaceBlockEntity#runProgram}。
     * 代码编译（含跨模块链接）与运行器缓存在方块实体上，跨 tick 复用以保持模块级变量（草稿 §3.8）；
     * 仅当依赖库内容变化才重建。
     */
    @Override
    public boolean runCode(Level level, BlockPos pos, BlockState state,
                           java.util.List<dev.programmablefurnace.base.CompilerAnalyzer.ModuleSource> sources) {
        if (level.isClientSide() || sources == null || sources.isEmpty()) return false;
        if (!(level.getBlockEntity(pos) instanceof ProgrammableFurnaceBlockEntity be)) return false;
        return be.runProgram(sources);
    }

    @Override
    protected BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ProgrammableFurnaceBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, FurnaceRegistration.BLOCK_ENTITY.get(), ProgrammableFurnaceBlockEntity::tick);
    }
}
