package dev.programmablefurnace.compiler;

import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.base.MachineConfigs;
import dev.programmablefurnace.item.CodeFileItem;
import dev.programmablefurnace.machine.MachineScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * 编译器 GUI —— 继承通用 {@link MachineScreen}，双模式（编译 / 依赖库）。
 * 工作界面：compiler_twoslot 背景 + "开始编译"按钮（进入二层 {@link CompileCodeScreen}）。
 */
public class CompilerScreen extends MachineScreen<CompilerMenu> {

    private static final Identifier TEXTURE_COMPILE =
            Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/compiler_twoslot.png");
    private static final Identifier TEXTURE_DEPENDENCY =
            Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/batch.png");

    private static final int START_BTN_X = GuiConfig.layout().compile.startBtnX;
    private static final int START_BTN_Y = GuiConfig.layout().compile.startBtnY;
    private static final int START_BTN_W = GuiConfig.layout().compile.startBtnW;
    private static final int START_BTN_H = GuiConfig.layout().compile.startBtnH;

    public CompilerScreen(CompilerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected Identifier getWorkTexture() { return TEXTURE_COMPILE; }

    @Override
    protected Identifier getDependencyTexture() { return TEXTURE_DEPENDENCY; }

    @Override
    protected Component getWorkTabLabel() {
        return Component.translatable("gui.programmablefurnace.tab.compile");
    }

    @Override
    protected void renderWorkBackground(GuiGraphicsExtractor graphics) {
        // 编译器工作界面只有背景 + 槽位，无额外渲染
    }

    @Override
    protected void rebuildWorkButtons() {
        Button startButton = Button.builder(Component.translatable("gui.programmablefurnace.btn.start_compile"), b -> {
            // 输出槽被占用则不可编译，提示并禁止进入
            if (!this.menu.getBlockEntity().getItem(CompilerBlockEntity.SLOT_OUTPUT).isEmpty()) {
                showHint("gui.programmablefurnace.hint.cannot_compile");
                return;
            }
            String initialCode = "";
            String title = MachineConfigs.compiler().defaultFileName();
            if (this.menu.slots.size() > 0) {
                var input = this.menu.slots.get(0).getItem();
                if (!input.isEmpty()) {
                    title = input.getHoverName().getString();
                    if (input.getItem() instanceof CodeFileItem) {
                        initialCode = CodeStorage.getCode(input);
                    }
                }
            }
            Minecraft.getInstance().setScreenAndShow(
                    new CompileCodeScreen(this.menu.getBlockEntity().getBlockPos(), initialCode, title, CompilerScreen.this));
        }).bounds(this.leftPos + START_BTN_X, this.topPos + START_BTN_Y, START_BTN_W, START_BTN_H).build();
        this.addRenderableWidget(startButton);
    }
}
