package dev.programmablefurnace.base;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 执行器接口：任何"运行编译代码"的方块（熔炉等）实现此接口。
 *
 * <p>传的是一批源文件而不是一个字符串：依赖库里可以有多个模块，按各自的 {@code MODULE("…")} 名
 * 互相 import（草稿 §2.4 / §4.9），所以编译入口必须看到整批文件才能做链接。</p>
 */
public interface IExecutor {

    /**
     * 运行依赖库里的这批代码：编译（含跨模块链接）+ 必要时重建运行器。
     *
     * @param sources 依赖库里每个可运行/依赖库文件的文件名与源码
     * @return true 表示这批代码里有可执行入口、运行器可用
     */
    boolean runCode(Level level, BlockPos pos, BlockState state, List<CompilerAnalyzer.ModuleSource> sources);
}
