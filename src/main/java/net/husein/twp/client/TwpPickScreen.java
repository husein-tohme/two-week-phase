package net.husein.twp.client;

import net.husein.twp.TwpFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Pick one of your single-player worlds. A copy of it becomes the Two Week Phase world. */
public class TwpPickScreen extends Screen {
    private static final int PER_PAGE = 5;
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, RED = 0xFFFF6060, GOLD = 0xFFFFD050;

    private final Screen parent;
    private final Screen home;         // the library, shown again once the world is in it
    private final String friendName;
    private List<TwpImport.Entry> worlds = List.of();
    private int page;
    private boolean working;
    private volatile String status = "";
    private String error = "";

    public TwpPickScreen(Screen parent, Screen home, String friendName) {
        super(Component.literal("Add a world from singleplayer"));
        this.parent = parent;
        this.home = home;
        this.friendName = friendName;
    }

    @Override
    protected void init() {
        if (working) return;                                  // nothing to press while the copy is being made
        worlds = TwpImport.list(minecraft);
        int pages = Math.max(1, (worlds.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int cx = width / 2, top = height / 2 - 60;
        for (int row = 0; row < PER_PAGE && page * PER_PAGE + row < worlds.size(); row++) {
            TwpImport.Entry e = worlds.get(page * PER_PAGE + row);
            addRenderableWidget(Button.builder(Component.literal(e.label()), b -> add(e)).bounds(cx - 150, top + row * 24, 300, 20).build());
        }
        int y = top + PER_PAGE * 24 + 4;
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> { page--; rebuildWidgets(); }).bounds(cx - 150, y, 90, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; rebuildWidgets(); }).bounds(cx + 60, y, 90, 20).build()).active = page < pages - 1;
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx - 50, y, 100, 20).build());
    }

    private void add(TwpImport.Entry e) {
        working = true;
        error = "";
        status = "Getting ready...";
        rebuildWidgets();
        Minecraft client = minecraft;
        TwpFiles.Owners owners = TwpScreen.newOwners(client, friendName, e.name());
        Thread t = new Thread(() -> {
            String problem = TwpImport.run(e.dir(), owners, (done, total) ->
                    status = "Copying \"" + e.name() + "\": " + (done >> 20) + " of " + (total >> 20) + " MB");
            client.execute(() -> {
                working = false;
                if (problem == null) {
                    CompatClient.setScreen(client, home);     // the library now lists it, with a Play button
                } else {
                    error = problem;
                    rebuildWidgets();
                }
            });
        }, "Two Week Phase: adding a world");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, top = height / 2 - 60;
        graphics.centeredText(font, title, cx, top - 40, GOLD);
        if (working) {
            graphics.centeredText(font, Component.literal(status), cx, height / 2 - 10, WHITE);
            graphics.centeredText(font, Component.literal("Please wait. Do not close the game."), cx, height / 2 + 6, GREY);
            return;
        }
        graphics.centeredText(font, Component.literal(worlds.isEmpty() ? "You have no singleplayer worlds here."
                : "Pick the world to share with " + friendName + "."), cx, top - 26, WHITE);
        graphics.centeredText(font, Component.literal("A copy is added and becomes hardcore. The original is not changed."), cx, top - 14, GREY);
        if (!error.isEmpty()) graphics.centeredText(font, Component.literal(error), cx, top + PER_PAGE * 24 + 32, RED);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !working;
    }

    @Override
    public void onClose() {
        if (!working) CompatClient.setScreen(minecraft, parent);
    }
}
