package net.husein.twp.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Add a world to the library: type the friend it is shared with, then start a new world or add one from singleplayer. */
public class TwpAddScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, RED = 0xFFFF6060, GOLD = 0xFFFFD050;

    private final Screen parent;       // the library
    private EditBox friend;
    private String error = "";

    public TwpAddScreen(Screen parent) {
        super(Component.literal("Add a world to the library"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2;
        String typed = friend == null ? "" : friend.getValue();
        friend = new EditBox(font, cx - 100, y - 4, 200, 20, Component.literal("Friend's Minecraft name"));
        friend.setMaxLength(16);
        friend.setValue(typed);
        addRenderableWidget(friend);
        setInitialFocus(friend);
        addRenderableWidget(Button.builder(Component.literal("Create a new world"), b -> {
            String problem = TwpScreen.create(minecraft, friend.getValue(), parent);
            error = problem == null ? "" : problem;
        }).bounds(cx - 100, y + 24, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Add a world from singleplayer"), b -> {
            String name = friend.getValue().trim();
            String problem = TwpScreen.checkFriend(minecraft, name);
            error = problem == null ? "" : problem;
            if (problem == null) CompatClient.setScreen(minecraft, new TwpPickScreen(this, parent, name));
        }).bounds(cx - 100, y + 48, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx - 100, y + 76, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, top = height / 2 - 64;
        graphics.centeredText(font, title, cx, top - 18, GOLD);
        graphics.centeredText(font, Component.literal("A hardcore world you and one friend share. You both keep it."), cx, top, WHITE);
        graphics.centeredText(font, Component.literal("Start a new one, or add a copy of one of your singleplayer worlds."), cx, top + 12, GREY);
        graphics.centeredText(font, Component.literal("Your friend's Minecraft name:"), cx, top + 24, GREY);
        if (!error.isEmpty()) graphics.centeredText(font, Component.literal(error), cx, height / 2 + 102, RED);
    }

    @Override
    public void onClose() {
        CompatClient.setScreen(minecraft, parent);
    }
}
