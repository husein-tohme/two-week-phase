package net.husein.twp.client;

import net.husein.twp.TwpCloud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Online saving: on or off, what happens when a save is finished, and where "online" is.
 * One owner connects a web storage once; the other pastes the library code the first one copies here.
 */
public class TwpOnlineScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GOLD = 0xFFFFD050, RED = 0xFFFF6060, GREEN = 0xFF70E070;
    private static final String KOOFR = "https://app.koofr.net/dav/Koofr";

    private final Screen parent;
    private EditBox code, address, email, password;
    private String message = "";
    private int messageColor = GREY;
    private boolean busy;

    public TwpOnlineScreen(Screen parent) {
        super(Component.literal("Online saving"));
        this.parent = parent;
    }

    private static String text(EditBox box, String otherwise) {
        return box == null ? otherwise : box.getValue();
    }

    @Override
    protected void init() {
        TwpCloud.Settings s = TwpCloud.settings();
        int cx = width / 2, y = height / 2 - 108;
        String typedCode = text(code, ""), typedAddress = text(address, s.url != null && !s.url.isBlank() ? s.url : KOOFR);
        String typedEmail = text(email, s.user == null ? "" : s.user), typedPassword = text(password, "");

        addRenderableWidget(Button.builder(Component.literal("Online saving: " + (s.online ? "ON" : "OFF")), b -> {
            s.online = !s.online;
            TwpCloud.saveSettings();
            rebuildWidgets();
        }).bounds(cx - 152, y + 14, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("When saved: " + (s.closeWhenDone ? "close Minecraft" : "tell me")), b -> {
            s.closeWhenDone = !s.closeWhenDone;
            TwpCloud.saveSettings();
            rebuildWidgets();
        }).bounds(cx + 2, y + 14, 150, 20).build());

        code = new EditBox(font, cx - 152, y + 72, 226, 20, Component.literal("Library code"));
        code.setMaxLength(800);
        code.setValue(typedCode);
        code.setHint(Component.literal("TWP1:..."));
        addRenderableWidget(code);
        addRenderableWidget(Button.builder(Component.literal("Connect"), b -> {
            String[] parts = TwpCloud.readCode(code.getValue());
            if (parts == null) say("That is not a library code. It starts with TWP1:", RED);
            else connect(parts[0], parts[1], parts[2]);
        }).bounds(cx + 78, y + 72, 74, 20).build()).active = !busy;

        address = new EditBox(font, cx - 152, y + 112, 304, 20, Component.literal("Storage address"));
        address.setMaxLength(300);
        address.setValue(typedAddress);
        addRenderableWidget(address);
        email = new EditBox(font, cx - 152, y + 136, 150, 20, Component.literal("Email"));
        email.setMaxLength(120);
        email.setValue(typedEmail);
        email.setHint(Component.literal("Email of the storage account"));
        addRenderableWidget(email);
        password = new EditBox(font, cx + 2, y + 136, 150, 20, Component.literal("App password"));
        password.setMaxLength(120);
        password.setValue(typedPassword);
        password.setHint(Component.literal(s.password != null && !s.password.isBlank() ? "(saved)" : "App password"));
        addRenderableWidget(password);

        boolean connected = s.url != null && !s.url.isBlank();
        addRenderableWidget(Button.builder(Component.literal("Connect"), b ->
                connect(address.getValue(), email.getValue(), password.getValue().isEmpty() && s.password != null ? s.password : password.getValue()))
                .bounds(cx - 152, y + 160, 98, 20).build()).active = !busy;
        addRenderableWidget(Button.builder(Component.literal("Copy library code"), b -> {
            minecraft.keyboardHandler.setClipboard(TwpCloud.code());
            say("Copied. Send it to your friend privately: whoever has it can change your worlds.", GREEN);
        }).bounds(cx - 49, y + 160, 98, 20).build()).active = connected;
        addRenderableWidget(Button.builder(Component.literal("Disconnect"), b -> {
            s.url = null;
            s.user = null;
            s.password = null;
            TwpCloud.saveSettings();
            say("Disconnected. Nothing online was removed.", GREY);
            rebuildWidgets();
        }).bounds(cx + 54, y + 160, 98, 20).build()).active = connected;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx - 100, y + 198, 200, 20).build());
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
    }

    /** Tries the storage first; only details that work are saved. */
    private void connect(String url, String user, String pass) {
        String u = url == null ? "" : url.trim();
        boolean local = u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1");
        if (!u.startsWith("https://") && !local) {
            say("The address has to start with https://", RED);
            return;
        }
        if (user == null || user.isBlank() || pass == null || pass.isBlank()) {
            say("Type the email and the app password of the storage account.", RED);
            return;
        }
        busy = true;
        say("Trying it...", GREY);
        rebuildWidgets();
        Minecraft client = minecraft;
        CompletableFuture.supplyAsync(() -> {
            try {
                return TwpCloud.test(TwpCloud.web(u, user.trim(), pass.trim()));
            } catch (Exception e) {
                return "That address does not work.";
            }
        }).whenComplete((problem, err) -> client.execute(() -> {
            busy = false;
            if (err != null || problem != null) {
                say("Not connected: " + (problem != null ? problem : "something went wrong"), RED);
            } else {
                TwpCloud.Settings s = TwpCloud.settings();
                s.url = u;
                s.user = user.trim();
                s.password = pass.trim();
                s.online = true;
                TwpCloud.saveSettings();
                password.setValue("");
                say("Connected. Worlds are saved online from now on.", GREEN);
            }
            rebuildWidgets();
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, y = height / 2 - 108;
        graphics.centeredText(font, title, cx, y, GOLD);
        TwpCloud.Settings s = TwpCloud.settings();
        TwpCloud.Store st = TwpCloud.store();
        String where = !s.online ? "Off: worlds stay on this PC, and with your friend the old way."
                : st == null ? "On, but nothing is connected yet."
                : st.exact() ? "On. Saved to " + st.name() + ". The mod tells you the moment a save is finished."
                : "On. Saved through the shared folder " + st.name();
        graphics.centeredText(font, Component.literal(TwoWeekPhaseClient.clip(minecraft, where, width - 20)), cx, y + 42, WHITE);
        graphics.centeredText(font, Component.literal("Got a library code from your friend? Paste it here:"), cx, y + 60, GREY);
        graphics.centeredText(font, Component.literal("Or connect your own web storage (once), then copy the code for your friend:"), cx, y + 100, GREY);
        graphics.centeredText(font, Component.literal(TwoWeekPhaseClient.clip(minecraft, message, width - 20)), cx, y + 185, messageColor);
    }

    @Override
    public void onClose() {
        CompatClient.setScreen(minecraft, parent);
    }
}
