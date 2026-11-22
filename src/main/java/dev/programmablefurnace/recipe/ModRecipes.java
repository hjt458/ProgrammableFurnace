package dev.programmablefurnace.recipe;

import dev.programmablefurnace.base.ModInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 配方序列化器注册。目前只有"转只读"（make_readonly）一条，但它属于 {@code recipe} 包，
 * 不该由入口类代管——之前 {@code MakeReadonlyRecipe} 为此反过来 import 入口类。
 */
public final class ModRecipes {

    private ModRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, ModInfo.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<MakeReadonlyRecipe>> MAKE_READONLY =
            RECIPE_SERIALIZERS.register("make_readonly",
                    () -> new RecipeSerializer<>(MakeReadonlyRecipe.CODEC, MakeReadonlyRecipe.STREAM));

    public static void register(IEventBus modEventBus) {
        RECIPE_SERIALIZERS.register(modEventBus);
    }
}
