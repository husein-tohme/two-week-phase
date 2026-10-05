package net.husein.twp.client;

import net.husein.twp.TwoWeekPhaseMod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Hand-over, host side. Our world is closed; the other owner's game is opening their newer copy.
 * As soon as Essential says they are hosting, we join them, and our copy is brought up to date.
 */
public class TwpWaitScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GOLD = 0xFFFFD050;

    private final Screen parent;
    private final UUID friend;
    private final String friendName;
    private final boolean essential = EssentialBridge.present();
    private int ticks;
    private boolean joining;

    public TwpWaitScreen(Screen parent, UUID friend, String friendName) {
        super(Component.literal("Two Week Phase"));
        this.parent = parent;
        this.friend = friend;
        this.friendName = friendName;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 - 100, height / 2 + 40, 200, 20).build());
    }

    @Override
    public void tick() {
        ticks++;
        if (joining || ticks % 20 != 0) return;
        String address = null;
        String test = TwoWeekPhaseMod.DEV ? System.getProperty("twp.swapJoin") : null;   // test hook: no Essential in the development environment
        if (test != null) {
            if (ticks >= 400) address = test;
        } else if (essential && ticks >= 100 && EssentialBridge.isHosting(friend)) {
            address = EssentialBridge.address(friend);
        }
        if (address == null) return;
        joining = true;
        TwoWeekPhaseMod.LOG.info("Hand-over: joining {} at {}", friendName, address);
        if (test == null) {
            EssentialBridge.join(minecraft, parent, friend, friendName + "'s world");
        } else {
            ConnectScreen.startConnecting(parent, minecraft, ServerAddress.parseString(address),
                    new ServerData(friendName + "'s world", address, ServerData.Type.OTHER), false, null);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, y = height / 2 - 40;
        graphics.centeredText(font, title, cx, y - 18, GOLD);
        graphics.centeredText(font, Component.literal(friendName + " has newer progress in this world."), cx, y, WHITE);
        if (essential && ticks < 3600) {
            graphics.centeredText(font, Component.literal("It is opening on " + friendName + "'s side. You join by yourself in a moment."), cx, y + 14, GREY);
            graphics.centeredText(font, Component.literal("Your PC then takes that progress. What it replaces is kept as a backup."), cx, y + 26, GREY);
        } else {
            graphics.centeredText(font, Component.literal("Ask " + friendName + " to press Play on this world in the library,"), cx, y + 14, GREY);
            graphics.centeredText(font, Component.literal("then join " + friendName + " yourself. Your PC then takes that progress."), cx, y + 26, GREY);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        CompatClient.setScreen(minecraft, parent);
    }
}
