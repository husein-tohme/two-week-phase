package net.husein.twp.client;

import net.husein.twp.TwoWeekPhaseMod;
import net.husein.twp.TwpCloud;
import net.husein.twp.TwpFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** The library: every shared world this PC holds. Play one, or add another. There is no delete button, on purpose. */
public class TwpScreen extends Screen {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final int PER_PAGE = 4, ROW = 28;
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GOLD = 0xFFFFD050, RED = 0xFFFF6060, GREEN = 0xFF70E070;

    private final Screen parent;
    private List<TwpFiles.Slot> worlds = List.of();
    private TwpCloud.Store cloud;
    private int page;

    public TwpScreen(Screen parent) {
        super(Component.literal("Two Week Phase library"));
        this.parent = parent;
    }

    /** Plays a library world. With a shared folder, the newest version is fetched first (or the other owner is joined). */
    public static void play(Minecraft client, TwpFiles.Slot slot, Screen back) {
        EssentialBridge.Live live = EssentialBridge.liveFor(client, slot);
        if (live != null) {                               // the other owner is in this world right now: go to them, do not open a second copy
            EssentialBridge.join(client, back, live.host(), slot.title());
            return;
        }
        if (TwpCloud.store() == null) open(client, slot, back);
        else CompatClient.setScreen(client, new TwpCloudScreen(back, slot));
    }

    /** Opens this PC's copy of a library world, as it is. */
    static void open(Minecraft client, TwpFiles.Slot slot, Screen back) {
        new WorldOpenFlows(client, LevelStorageSource.createDefault(slot.worldsDir()))
                .openWorld(TwpFiles.LEVEL, () -> CompatClient.setScreen(client, back));
    }

    /** True if Play can do something: this PC has the world, or it can be fetched from online. */
    static boolean playable(TwpFiles.Slot slot) {
        return slot.ready() || TwpCloud.store() != null;
    }

    private static long lookedOnline;

    /** Worlds that are online but not on this PC yet join the library. Looked up in the background, at most once a minute. */
    static void lookOnline(Minecraft client, Runnable whenFound) {
        long now = System.currentTimeMillis();
        if (now - lookedOnline < 60_000 || TwpCloud.store() == null) return;
        lookedOnline = now;
        String me = client.getUser().getName();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> TwpCloud.adopt(me)).thenAccept(n -> {
            if (n > 0) client.execute(whenFound);
        });
    }

    /** What is wrong with sharing a world with this friend, or null if nothing is. */
    public static String checkFriend(Minecraft client, String friendName) {
        if (!NAME.matcher(friendName).matches()) return "Type your friend's exact Minecraft name (letters, numbers and _ only).";
        if (friendName.equalsIgnoreCase(client.getUser().getName())) return "That is your own name. Type your friend's.";
        return null;
    }

    /** The record of the two owners of a new library world: the player and one friend. */
    public static TwpFiles.Owners newOwners(Minecraft client, String friendName, String title) {
        String me = client.getUser().getName();
        TwpFiles.Owners owners = new TwpFiles.Owners();
        TwpFiles.Owner a = new TwpFiles.Owner();
        a.name = me;
        UUID id = client.getUser().getProfileId();
        a.uuid = id == null ? null : id.toString();
        TwpFiles.Owner b = new TwpFiles.Owner();
        b.name = friendName;
        owners.players.add(a);
        owners.players.add(b);
        owners.createdBy = me;
        owners.created = Instant.now().toString();
        owners.lineage = UUID.randomUUID().toString();
        owners.title = title;
        return owners;
    }

    /** "Two Week Phase", or "Two Week Phase 2", "Two Week Phase 3"... if the library already has that name. */
    private static String freshTitle() {
        List<String> taken = TwpFiles.slots().stream().map(TwpFiles.Slot::title).toList();
        if (!taken.contains(TwpFiles.LEVEL)) return TwpFiles.LEVEL;
        for (int n = 2; ; n++) {
            String t = TwpFiles.LEVEL + " " + n;
            if (!taken.contains(t)) return t;
        }
    }

    /** Creates a brand new world for the player and one friend. Returns what went wrong, or null if it started. */
    public static String create(Minecraft client, String friendName, Screen back) {
        friendName = friendName == null ? "" : friendName.trim();
        String problem = checkFriend(client, friendName);
        if (problem != null) return problem;

        TwpFiles.Owners owners = newOwners(client, friendName, freshTitle());
        TwpFiles.Slot slot = TwpFiles.newSlot(owners.lineage);
        try {
            Files.createDirectories(slot.worldsDir());
        } catch (Exception e) {
            TwoWeekPhaseMod.LOG.error("Could not create {}", slot.worldsDir(), e);
            return "Could not create the world folder inside the mods folder.";
        }
        TwpFiles.write(slot.sealedFile(), owners);   // from this moment the two owners are fixed for good

        // Always hardcore, survival, hard, no cheats. None of this can be chosen.
        LevelSettings info = new LevelSettings(owners.title, GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.HARD, true, true), false, WorldDataConfiguration.DEFAULT);
        new WorldOpenFlows(client, LevelStorageSource.createDefault(slot.worldsDir())).createFreshLevel(TwpFiles.LEVEL, info,
                WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, back);
        return null;
    }

    private static String friendOf(Minecraft client, TwpFiles.Owners o) {
        String me = client.getUser().getName();
        return o.players.get(0).name.equalsIgnoreCase(me) ? o.players.get(1).name : o.players.get(0).name;
    }

    @Override
    protected void init() {
        lookOnline(minecraft, this::rebuildWidgets);
        worlds = TwpFiles.slots();
        cloud = TwpCloud.store();
        int pages = Math.max(1, (worlds.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int cx = width / 2, top = height / 2 - 70;
        for (int row = 0; row < PER_PAGE && page * PER_PAGE + row < worlds.size(); row++) {
            TwpFiles.Slot slot = worlds.get(page * PER_PAGE + row);
            boolean can = playable(slot);
            addRenderableWidget(Button.builder(Component.literal(can ? "Play" : "Not here yet"), b -> play(minecraft, slot, this))
                    .bounds(cx + 70, top + row * ROW + 2, 80, 20).build()).active = can;
        }
        int y = top + PER_PAGE * ROW + 4;
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(cx - 156, y, 20, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(cx + 136, y, 20, 20).build()).active = page < pages - 1;
        }
        addRenderableWidget(Button.builder(Component.literal("Add a world"), b -> CompatClient.setScreen(minecraft, new TwpAddScreen(this))).bounds(cx - 132, y, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Online saving"), b -> CompatClient.setScreen(minecraft, new TwpOnlineScreen(this))).bounds(cx - 43, y, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx + 46, y, 86, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, top = height / 2 - 70;
        graphics.centeredText(font, title, cx, top - 30, GOLD);
        int foot = top + PER_PAGE * ROW + 30;
        String note = TwoWeekPhaseMod.cloudNote;
        if (note != null && System.currentTimeMillis() - TwoWeekPhaseMod.cloudNoteAt < 30 * 60_000) {
            graphics.centeredText(font, Component.literal(TwoWeekPhaseClient.clip(minecraft, note, width - 20)), cx, foot, note.contains("NOT") ? RED : GREEN);
            foot += 12;
        }
        TwpSave job = TwpSave.current;
        String online = job != null && job.running() ? "Saving \"" + job.title + "\" online: " + (job.done >> 20) + " of " + Math.max(1, job.total >> 20) + " MB..."
                : !TwpCloud.settings().online ? "Online saving: OFF"
                : cloud == null ? "Online saving: ON, but nothing is connected yet (press Online saving)"
                : "Online saving: ON (" + cloud.name() + ")";
        graphics.centeredText(font, Component.literal(TwoWeekPhaseClient.clip(minecraft, online, width - 20)), cx, foot, GREY);
        if (worlds.isEmpty()) {
            graphics.centeredText(font, Component.literal("Your library is empty."), cx, top + 20, WHITE);
            graphics.centeredText(font, Component.literal("Add a world to share it with a friend. You both keep it."), cx, top + 34, GREY);
            return;
        }
        graphics.centeredText(font, Component.literal("Worlds you share with a friend. You both keep a copy of each."), cx, top - 16, GREY);
        for (int row = 0; row < PER_PAGE && page * PER_PAGE + row < worlds.size(); row++) {
            TwpFiles.Slot slot = worlds.get(page * PER_PAGE + row);
            TwpFiles.Owners o = slot.sealed();
            int y = top + row * ROW;
            graphics.fill(cx - 152, y, cx + 152, y + ROW - 4, 0x80000000);
            graphics.text(font, TwoWeekPhaseClient.clip(minecraft, slot.title(), 210), cx - 146, y + 3, WHITE);
            String sub = o == null ? "" : "with " + friendOf(minecraft, o)
                    + (slot.ready() ? "" : playable(slot) ? "  -  Play fetches it from online"
                    : "  -  join " + friendOf(minecraft, o) + "'s game once to get it");
            graphics.text(font, TwoWeekPhaseClient.clip(minecraft, sub, 210), cx - 146, y + 13, GREY);
        }
    }

    @Override
    public void onClose() {
        CompatClient.setScreen(minecraft, parent);
    }
}
