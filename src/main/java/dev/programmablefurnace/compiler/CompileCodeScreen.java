package dev.programmablefurnace.compiler;
import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.base.MachineConfigs;
import dev.programmablefurnace.network.CompilePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 编译器 GUI —— 第二层（代码输入界面）：不读取玩家物品栏、不含 Container。
 * 中间一大块为代码输入框，右侧为竖排"编译"按钮。
 * 点击"编译"通过 CompilePacket 把代码发到服务端执行编译，随后关闭 GUI。
 */
public class CompileCodeScreen extends Screen {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/code.png");

    // 布局来自 GuiConfig.layout()（gui_config.json）：panel/editor/compileBtn
    private static final int W = GuiConfig.layout().panel.w;
    private static final int H = GuiConfig.layout().panel.h;
    // 中间整块输入框（占据面板内部，去掉顶部提示文字）
    private static final int EDIT_X = GuiConfig.layout().editor.x;
    private static final int EDIT_Y = GuiConfig.layout().editor.y;
    private static final int EDIT_W = GuiConfig.layout().editor.w;
    private static final int EDIT_H = GuiConfig.layout().editor.h;
    // 编译按钮：放在整个屏幕 UI 的右侧，脱离中间 GUI 框
    private static final int BTN_W = GuiConfig.layout().compileBtn.w;
    private static final int BTN_H = GuiConfig.layout().compileBtn.h;

    private final BlockPos pos;
    private final String initialCode;
    private final String title;  // 打开的编码文件标题（浏览器标签式，显示在顶部标题条）
    private final Screen back;   // 编译后返回的目标界面（一层 CompilerScreen）；null 则关闭 GUI
    private CodeEditorWidget editor;
    private int cx;
    private int cy;

    public CompileCodeScreen(BlockPos pos) {
        this(pos, "", null, null);
    }

    public CompileCodeScreen(BlockPos pos, String initialCode) {
        this(pos, initialCode, null, null);
    }

    public CompileCodeScreen(BlockPos pos, String initialCode, Screen back) {
        this(pos, initialCode, null, back);
    }

    /** title：打开的编码文件标题（显示在顶部标题条）。initialCode：若放入编码文件则预填已有代码。back：编译后返回目标。 */
    public CompileCodeScreen(BlockPos pos, String initialCode, String title, Screen back) {
        super(Component.translatable("container.programmablefurnace.compiler"));
        this.pos = pos;
        this.initialCode = initialCode == null ? "" : initialCode;
        this.title = (title == null || title.isEmpty()) ? MachineConfigs.compiler().defaultFileName() : title;
        this.back = back;
    }

    @Override
    protected void init() {
        super.init();
        this.cx = (this.width - W) / 2;
        this.cy = (this.height - H) / 2;

        // 窗口尺寸变化会再次进入 init()：编辑器只创建一次，之后只挪位置。
        // 以前每次都 new + setValue(initialCode)，玩家改窗口大小或切全屏就把已经敲了一半的代码丢了。
        if (this.editor == null) {
            this.editor = new CodeEditorWidget(this.font, this.cx + EDIT_X, this.cy + EDIT_Y, EDIT_W, EDIT_H);
            this.editor.setValue(this.initialCode);
            this.editor.setFocused(true);
        } else {
            this.editor.setPosition(this.cx + EDIT_X, this.cy + EDIT_Y);
        }

        // 编译按钮：贴在屏幕最右侧、垂直居中（脱离中间 GUI 框）
        int btnX = this.width - BTN_W - 16;
        int btnY = this.cy + (H - BTN_H) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.programmablefurnace.btn.compile"), b -> doCompile())
                .bounds(btnX, btnY, BTN_W, BTN_H)
                .build());
    }

    private void doCompile() {
        var mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (connection != null) {
            connection.send(new CompilePacket(this.pos, this.editor.getValue()));
        }
        if (this.back != null) {
            // 编译后返回一层物品界面：不关闭容器菜单，物品/输出状态由服务端同步刷新
            mc.setScreenAndShow(this.back);
        } else {
            // 无返回目标（老用法）：关闭第一层的容器菜单（物品回玩家背包），再关整个 GUI
            if (mc.player != null) {
                mc.player.closeContainer();
            }
            mc.setScreenAndShow(null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        // 顶部标题条（y 1~18）：显示打开的编码文件标题
        graphics.text(this.font, Component.literal(this.title), this.cx + 8, this.cy + 5, 0xFFFFFFFF);
        // 代码编辑器（行号 + 文本 + 滚动条）
        if (this.editor != null) {
            this.editor.render(graphics);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, this.cx, this.cy, 0.0F, 0.0F, W, H, W, H);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean r = false;
        if (this.editor != null && this.editor.isFocused()) {
            r = this.editor.keyPressed(event);
        }
        if (r) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        boolean r = false;
        if (this.editor != null && this.editor.isFocused()) {
            r = this.editor.charTyped(event);
        }
        if (r) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.editor != null && this.editor.mouseClicked(event)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.editor != null && this.editor.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
