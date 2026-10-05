package net.husein.twp.client;

import net.husein.twp.TwpCloud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shows a session being put online: how far it is, and clearly when it is finished. Can close the game afterwards. */
public class TwpSaveScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GOLD = 0xFFFFD050, RED = 0xFFFF6060, GREEN = 0xFF70E070;
    private static final int CLOSE_AFTER_SECONDS = 15;

    private final Screen parent;
    private final TwpSave job;
    private boolean shownDone;
    private boolean stay;

    public TwpSaveScreen(Screen parent, TwpSave job) {
        super(Component.literal("Saving online"));
        this.parent = parent;
        this.job = job;
    }

    private boolean closing() {
        return !job.running() && job.ok && !stay && TwpCloud.settings().closeWhenDone;
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 + 34;
        shownDone = !job.running();
        if (job.running()) {
            addRenderableWidget(Button.builder(Component.literal("Do other things meanwhile"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
        } else if (closing()) {
            addRenderableWidget(Button.builder(Component.literal("Don't close Minecraft"), b -> { stay = true; rebuildWidgets(); }).bounds(cx - 100, y, 200, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("OK"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
        }
    }

    @Override
    public void tick() {
        if (!job.running() && !shownDone) rebuildWidgets();
        if (closing() && System.currentTimeMillis() - job.finishedAt > CLOSE_AFTER_SECONDS * 1000L) minecraft.stop();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, y = height / 2 - 40;
        graphics.centeredText(font, Component.literal(job.title), cx, y - 18, GOLD);
        if (job.running()) {
            long total = job.total, done = job.done;
            graphics.centeredText(font, Component.literal(total > 0 ? "Putting your progress online: " + (done >> 20) + " of " + Math.max(1, total >> 20) + " MB"
                    : "Putting your progress online: checking what changed..."), cx, y, WHITE);
            int w = 200, x = cx - w / 2;
            graphics.fill(x - 1, y + 17, x + w + 1, y + 29, 0xFF000000);
            graphics.fill(x, y + 18, x + (total > 0 ? (int) (w * Math.min(1.0, (double) done / total)) : 0), y + 28, GREEN);
            graphics.centeredText(font, Component.literal("Keep Minecraft open and your PC on until this says it is finished."), cx, y + 40, GREY);
            return;
        }
        graphics.centeredText(font, Component.literal(job.ok ? "FINISHED" : "NOT FINISHED"), cx, y, job.ok ? GREEN : RED);
        graphics.centeredText(font, Component.literal(TwoWeekPhaseClient.clip(minecraft, job.result, width - 20)), cx, y + 16, WHITE);
        if (closing()) {
            long left = CLOSE_AFTER_SECONDS - (System.currentTimeMillis() - job.finishedAt) / 1000;
            graphics.centeredText(font, Component.literal("Minecraft closes by itself in " + Math.max(0, left) + " seconds."), cx, y + 40, GREY);
        } else if (!job.ok) {
            graphics.centeredText(font, Component.literal("It is tried again the next time you are on the title screen."), cx, y + 40, GREY);
        }
    }

    @Override
    public void onClose() {
        CompatClient.setScreen(minecraft, parent);
    }
}
