package dev.programmablefurnace.recipe;

import dev.programmablefurnace.recipe.ModRecipes;
import com.mojang.serialization.MapCodec;
import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.ContentState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/**
 * “转只读”配方：任意可执行文件（EXECUTABLE）+ 1 金粒 → 保留原程序数据、仅把内容状态改为 READONLY（只读可执行）。
 * 产物由输入可执行文件实时复制构造（保留 code/source_name/file_type 等 CUSTOM_DATA），状态置 READONLY；
 * 而非固定 result（固定 result 无法携带玩家的程序数据）。
 *
 * <p>序列化复用 {@link ShapelessRecipe} 的 codec / stream codec（recipe json 的 type=programmablefurnace:make_readonly），
 * 仅 {@link #matches}/{@link #assemble} 覆盖：额外要求输入含 EXECUTABLE 状态的可执行文件，
 * 合成结果从该输入复制并置只读。
 */
public class MakeReadonlyRecipe implements CraftingRecipe {

    public static final MapCodec<MakeReadonlyRecipe> CODEC =
            ShapelessRecipe.MAP_CODEC.xmap(MakeReadonlyRecipe::new, r -> r.delegate);
    public static final StreamCodec<RegistryFriendlyByteBuf, MakeReadonlyRecipe> STREAM =
            ShapelessRecipe.STREAM_CODEC.map(MakeReadonlyRecipe::new, r -> r.delegate);

    private final ShapelessRecipe delegate;

    public MakeReadonlyRecipe(ShapelessRecipe delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (!delegate.matches(input, level)) return false;
        for (ItemStack s : input.items())
            if (!s.isEmpty() && CodeStorage.getContentState(s) == ContentState.EXECUTABLE) return true;
        return false;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        for (ItemStack s : input.items()) {
            if (!s.isEmpty() && CodeStorage.getContentState(s) == ContentState.EXECUTABLE) {
                ItemStack out = s.copy();
                out.setCount(1);
                CodeStorage.setContentState(out, ContentState.READONLY);
                return out;
            }
        }
        return delegate.assemble(input);
    }

    @Override
    public RecipeSerializer<? extends CraftingRecipe> getSerializer() {
        return ModRecipes.MAKE_READONLY.get();
    }

    @Override
    public RecipeType<CraftingRecipe> getType() {
        return RecipeType.CRAFTING;
    }

    @Override
    public CraftingBookCategory category() { return delegate.category(); }

    @Override
    public String group() { return delegate.group(); }

    @Override
    public boolean showNotification() { return delegate.showNotification(); }

    @Override
    public PlacementInfo placementInfo() { return delegate.placementInfo(); }

    @Override
    public RecipeBookCategory recipeBookCategory() { return delegate.recipeBookCategory(); }
}
