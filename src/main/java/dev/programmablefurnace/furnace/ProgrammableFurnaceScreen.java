package dev.programmablefurnace.furnace;

import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.machine.MachineScreen;
import dev.programmablefurnace.network.FurnaceRunPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.List;
import net.minecraft.world.entity.player.Inventory;

/**
 * 可编程熔炉 GUI —— 继承通用 {@link MachineScreen}，双模式（工作 / 依赖库）。
 * 工作界面：原版熔炉背景 + 燃料火焰 + 进度箭头 + 右侧"开始/终止"按钮（依赖库有可执行文件时显示）；
 * 依赖库界面复用基类。
 */
public class ProgrammableFurnaceScreen extends MachineScreen<ProgrammableFurnaceMenu> {

    private static final Identifier TEXTURE_WORK =
            Identifier.fromNamespaceAndPath("minecraft", "textures/gui/container/furnace.png");
    private static final Identifier TEXTURE_DEPENDENCY =
            Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/batch.png");
    // 26.3 原版熔炉火焰/箭头用独立贴图（blitSprite），不再从 furnace.png 坐标截取
    private static final Identifier LIT_PROGRESS_SPRITE =
            Identifier.withDefaultNamespace("container/furnace/lit_progress");
    private static final Identifier BURN_PROGRESS_SPRITE =
            Identifier.withDefaultNamespace("container/furnace/burn_progress");

    private static final int START_X = GuiConfig.layout().furnace.startX;
    private static final int START_Y = GuiConfig.layout().furnace.startY;
    private static final int STOP_X = GuiConfig.layout().furnace.stopX;
    private static final int STOP_Y = GuiConfig.layout().furnace.stopY;
    private static final int BTN_W = GuiConfig.layout().furnace.btnW;
    private static final int BTN_H = GuiConfig.layout().furnace.btnH;

    private Button startButton;
    private Button stopButton;
    /** 按钮 pending 状态：null=无；0=期待停、1=期待跑（onPress 乐观设置，服务端 running 同步到位后清除，避免闪烁） */
    private Integer pendingRun = null;

    public ProgrammableFurnaceScreen(ProgrammableFurnaceMenu menu, Inventory playerInventory, Component title) {
        // 工作界面窗口宽度按 guiMode（背景图面板宽：111=176, 212=178, 313=218, 414=258），否则宽背景图会被截断
        super(menu, playerInventory, title, guiWidth(menu), 166);
        // 不显示容器标题（可编程熔炉）：把基类标题推出屏幕外
        this.titleLabelY = 1000;
    }

    /** 按当前 guiMode 取背景图面板宽度（窗口宽），未知模式回退 176。 */
    private static int guiWidth(ProgrammableFurnaceMenu menu) {
        GuiConfig.GuiMode gm = GuiConfig.mode(menu.getBlockEntity().getGuiMode());
        return gm != null ? gm.panelW : 176;
    }

    @Override
    protected Identifier getWorkTexture() {
        // ContainerData index4 = guiMode：按脚本 setGui 切换的背景图（111 也用用户改好的 furnace_111）
        return switch (this.menu.getDataValue(FurnaceDataIndex.GUI_MODE)) {
            case 212 -> Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/furnace_212.png");
            case 313 -> Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/furnace_313.png");
            case 414 -> Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/furnace_414.png");
            default -> Identifier.fromNamespaceAndPath("programmablefurnace", "textures/gui/furnace_111.png");
        };
    }

    @Override
    protected Identifier getDependencyTexture() { return TEXTURE_DEPENDENCY; }

    @Override
    protected Component getWorkTabLabel() {
        return Component.translatable("gui.programmablefurnace.tab.work");
    }

    @Override
    protected void renderWorkBackground(GuiGraphicsExtractor graphics) {
        int x = this.leftPos;
        int y = this.topPos;
        // 燃料火焰：26.3 用 lit_progress 贴图，flameH = ceil(burn/lit*13)+1（燃烧中渐降）
        // lit 缺失（旧存档/燃时未重设）时按满格兜底，保证燃烧中一定显示火焰
        int burn = this.menu.getDataValue(FurnaceDataIndex.BURN_TIME);
        int lit = this.menu.getDataValue(FurnaceDataIndex.LIT_DURATION);
        // 火焰/箭头叠加坐标按 guiMode（index7）从 gui_config.json 的 modes 表取，匹配对应背景图
        GuiConfig.GuiMode gm = GuiConfig.mode(this.menu.getDataValue(FurnaceDataIndex.GUI_MODE));
        if (gm == null) gm = GuiConfig.mode(111);
        if (burn > 0) {
            int flameH = lit > 0 ? Math.min(14, Mth.ceil(burn * 13.0f / lit) + 1) : 14;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LIT_PROGRESS_SPRITE,
                    14, 14, 0, 14 - flameH, x + gm.flameX, y + gm.flameY + 14 - flameH, 14, flameH);
        }
        // 进度箭头：26.3 用 burn_progress 贴图；每条箭头按对应输入槽进度（ContainerData index i，各自独立动画）
        if (gm.arrowX != null)
            for (int i = 0; i < gm.arrowX.length; i++) {
                int maxP = Math.max(1, this.menu.getDataValue(FurnaceDataIndex.BURN_MAX_PROGRESS));
                int p = Math.min(this.menu.getDataValue(FurnaceDataIndex.progress(i)), maxP);
                int arrowW = Math.min(24, Mth.ceil(p * 24.0f / maxP));
                if (arrowW > 0)
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BURN_PROGRESS_SPRITE,
                            24, 16, 0, 0, x + gm.arrowX[i], y + gm.arrowY[i], arrowW, 16);
            }
    }

    /** 覆盖内容阶段渲染：在槽位 tooltip 之后叠加火焰/箭头图标的进度比 tooltip（用传入的 scaled 鼠标坐标，修复 GUI 缩放下的 hover 判定）。 */
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractContents(graphics, mouseX, mouseY, delta);
        if (mode() != Mode.WORK) return;   // 仅工作界面有火焰/箭头
        GuiConfig.GuiMode gm = GuiConfig.mode(this.menu.getDataValue(FurnaceDataIndex.GUI_MODE));
        if (gm == null) gm = GuiConfig.mode(111);
        int burn = this.menu.getDataValue(FurnaceDataIndex.BURN_TIME);
        int lit = this.menu.getDataValue(FurnaceDataIndex.LIT_DURATION);
        if (burn > 0 && hit(mouseX, mouseY, this.leftPos + gm.flameX, this.topPos + gm.flameY, 14, 14)) {
            graphics.setComponentTooltipForNextFrame(this.font,
                    List.of(Component.literal(burn + " / " + lit)), mouseX, mouseY);
            return;
        }
        if (gm.arrowX != null) {
            int maxP = Math.max(1, this.menu.getDataValue(FurnaceDataIndex.BURN_MAX_PROGRESS));
            for (int i = 0; i < gm.arrowX.length; i++) {
                if (hit(mouseX, mouseY, this.leftPos + gm.arrowX[i], this.topPos + gm.arrowY[i], 24, 16)) {
                    int p = Math.min(this.menu.getDataValue(FurnaceDataIndex.progress(i)), maxP);
                    graphics.setComponentTooltipForNextFrame(this.font,
                            List.of(Component.literal(p + " / " + maxP)), mouseX, mouseY);
                    break;
                }
            }
        }
    }

    /** 鼠标是否落在矩形内（图标 hover 判定）。 */
    private static boolean hit(double mx, double my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }

    @Override
    protected void rebuildWorkButtons() {
        // 先移除旧按钮，避免重复叠加
        if (this.startButton != null) { this.removeWidget(this.startButton); this.startButton = null; }
        if (this.stopButton != null) { this.removeWidget(this.stopButton); this.stopButton = null; }
        this.pendingRun = null;   // 重建时按菜单同步值重新初始化，清掉旧的 pending
        // 依赖库里有可执行文件才显示"开始/终止"按钮（GUI 框外右侧）
        ProgrammableFurnaceBlockEntity be = this.menu.getBlockEntity();
        if (!be.hasExecutableProgram()) return;

        this.startButton = Button.builder(
                Component.translatable("gui.programmablefurnace.btn.start"),
                b -> {
                    Minecraft.getInstance().getConnection().send(
                            new FurnaceRunPacket(be.getBlockPos(), true));
                    // 本地立即切换可用性（不等服务端同步回包），终止按钮立刻可点
                    this.startButton.active = false;
                    this.stopButton.active = true;
                    this.pendingRun = 1;
                })
                .bounds(this.leftPos + START_X, this.topPos + START_Y, BTN_W, BTN_H)
                .build();
        this.stopButton = Button.builder(
                Component.translatable("gui.programmablefurnace.btn.stop"),
                b -> {
                    Minecraft.getInstance().getConnection().send(
                            new FurnaceRunPacket(be.getBlockPos(), false));
                    this.startButton.active = true;
                    this.stopButton.active = false;
                    this.pendingRun = 0;
                })
                .bounds(this.leftPos + STOP_X, this.topPos + STOP_Y, BTN_W, BTN_H)
                .build();
        // 初始互斥：运行中禁用"开始"，未运行禁用"终止"（经菜单 ContainerData 同步的 running）
        boolean run = this.menu.getDataValue(FurnaceDataIndex.RUNNING) != 0;
        this.startButton.active = !run;
        this.stopButton.active = run;
        this.addRenderableWidget(this.startButton);
        this.addRenderableWidget(this.stopButton);
    }

    /** 每帧校准按钮：依赖库可执行状态变化 → 增删按钮；按同步的 running 校准可用性。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        boolean has = this.menu.getBlockEntity().hasExecutableProgram();
        boolean hasBtn = this.startButton != null;
        if (has != hasBtn) rebuildWorkButtons();   // 打开 GUI 首帧依赖库槽未同步时为 false，同步完成后自动补建
        if (this.startButton == null || this.stopButton == null) return;
        boolean run = this.menu.getDataValue(FurnaceDataIndex.RUNNING) != 0;
        if (this.pendingRun != null) {
            // 服务端 running 已同步到位 → 清除 pending，继续正常校准（此时按钮即目标状态，无闪烁）
            if ((this.pendingRun == 1) == run) this.pendingRun = null;
            else return;   // 同步未到：保持 onPress 的乐观状态，不被旧同步值覆盖回去
        }
        this.startButton.active = !run;
        this.stopButton.active = run;
    }
}
