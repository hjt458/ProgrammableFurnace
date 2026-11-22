package dev.programmablefurnace.datagen;


import dev.programmablefurnace.base.ModInfo;
import com.google.common.hash.Hashing;

import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 数据生成：
 * <ul>
 *   <li>生成 GameTest 结构模板 {@code data/<modid>/structure/compiler_test.nbt}（3×3×3 石头立方，
 *       测试函数内会 setBlock 放置编译器）；</li>
 *   <li>GameTest 测试实例（test_instance）为 datapack JSON，放在
 *       {@code src/main/resources/data/<modid>/test_instance/*.json}，由游戏自动加载，无需在此注册。</li>
 * </ul>
 * 运行：{@code gradlew runData} 生成结构；随后 {@code gradlew runGameTestServer} 跑集成测试。
 */
public final class ModDataGen {

    private ModDataGen() {}

    /** 注册 datagen provider。 */
    public static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(StructureNbtProvider::new);
    }

    /** 生成 GameTest 结构模板：一个 3×3×3 的石头立方。 */
    public static final class StructureNbtProvider implements DataProvider {

        private final PackOutput output;

        public StructureNbtProvider(PackOutput output) {
            this.output = output;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            // 结构输出到 data pack：data/<modid>/structure/compiler_test.nbt
            java.nio.file.Path path = output.getOutputFolder(PackOutput.Target.DATA_PACK)
                    .resolve(ModInfo.MODID + "/structure/compiler_test.nbt");
            // 必须走 cache 机制写入，否则 Gradle datagen 运行在隔离文件系统，文件不会同步回真实磁盘
            return CompletableFuture.runAsync(() -> {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    NbtIo.writeCompressed(buildStructure(), baos);
                    byte[] bytes = baos.toByteArray();
                    cache.writeIfNeeded(path, bytes, Hashing.sha1().hashBytes(bytes));
                } catch (IOException e) {
                    throw new RuntimeException("生成 GameTest 结构模板失败", e);
                }
            });
        }

        private static CompoundTag buildStructure() {
            CompoundTag root = new CompoundTag();
            // size 必须是 IntTag 列表（ListTag），不能用 putIntArray（IntArrayTag），否则 load 时读成 size=0
            root.put("size", intList(3, 3, 3));

            // palettes: ListTag<CompoundTag>（单层，每个 CompoundTag 是一个 block state；loadPalette 用 getCompoundOrEmpty 遍历）
            ListTag palettes = new ListTag();
            CompoundTag stone = new CompoundTag();
            stone.putString("Name", "minecraft:stone");
            palettes.add(stone);
            CompoundTag compiler = new CompoundTag();
            compiler.putString("Name", "programmablefurnace:compiler");
            palettes.add(compiler);
            root.put("palettes", palettes);

            // blocks: 27 个 stone，state 引用 palette[0][0]
            ListTag blocks = new ListTag();
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    for (int x = 0; x < 3; x++) {
                        CompoundTag b = new CompoundTag();
                        b.put("pos", intList(x, y, z));
                        b.putInt("state", (x == 0 && y == 1 && z == 0) ? 1 : 0);
                        blocks.add(b);
                    }
                }
            }
            root.put("blocks", blocks);
            root.put("entities", new ListTag());
            return root;
        }

        private static ListTag intList(int... values) {
            ListTag list = new ListTag();
            for (int v : values) {
                list.add(net.minecraft.nbt.IntTag.valueOf(v));
            }
            return list;
        }

        @Override
        public String getName() {
            return "Programmable Furnace GameTest structures";
        }
    }
}
