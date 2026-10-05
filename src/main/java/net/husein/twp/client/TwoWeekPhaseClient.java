package net.husein.twp.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.husein.twp.TwoWeekPhaseMod;
import net.husein.twp.TwpCloud;
import net.husein.twp.TwpFiles;
import net.husein.twp.TwpNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The part that runs in each owner's game: the button on the title screen, and receiving
 * a copy of the world while playing in the other owner's game.
 */
public class TwoWeekPhaseClient implements ClientModInitializer {
    private static int autoStep;          // test hook state: which step of -Dtwp.auto is next
    private static boolean autoBusy;      // a step is running: we are heading into, or are in, the world
    private static int inWorldTicks;
    private static int exitCheck;

    /** true once the game we are connected to has shown it hosts OUR world (same two owners) */
    private boolean trusted;
    private boolean toldOnce;
    private int incomingId = -1;
    private int incomingNext;
    private Path incomingFile;
    private OutputStream incomingOut;
    /** the library world the game we are connected to is hosting */
    private TwpFiles.Slot incomingSlot;
    /** counts down after we open one of our worlds; at zero it is shared with the other owner */
    private int shareIn = -1;
    /** true while our copy is being brought up to date with the other owner's newer progress: what gets replaced is kept */
    private boolean keepOld;
    /** the id of the library world we are hosting right now, if any */
    private String hosting;
    private int beatTicks;
    /** the library world we are hosting, during its first minute: if the other owner opened it too, one of us joins the other */
    private TwpFiles.Slot justOpened;
    private int openedTicks;
    /** while we are on our way into someone's game, nothing else may take over the screen */
    static volatile long joiningUntil;
    /** hand-over, guest side: the world to open by ourselves as soon as the old host has closed theirs */
    private static TwpFiles.Slot openNext;
    private static long openNextUntil;
    private int nextRequestId = 1;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(TwpNet.Welcome.TYPE, (p, ctx) -> ctx.client().execute(() -> onWelcome(ctx.client(), p)));
        ClientPlayNetworking.registerGlobalReceiver(TwpNet.Data.TYPE, (p, ctx) -> ctx.client().execute(() -> onData(ctx.client(), p)));
        ClientPlayNetworking.registerGlobalReceiver(TwpNet.Done.TYPE, (p, ctx) -> ctx.client().execute(() -> onDone(ctx.client(), p)));
        ClientPlayNetworking.registerGlobalReceiver(TwpNet.Swap.TYPE, (p, ctx) -> ctx.client().execute(() -> {
            TwpFiles.Slot slot = trusted ? TwpFiles.byLineage(p.lineage()) : null;
            if (slot == null || !slot.ready()) return;
            openNext = slot;
            openNextUntil = System.currentTimeMillis() + 120_000;
            chat(ctx.client(), "you have the newer progress. In a few seconds this world opens from YOUR library and the other owner joins you.", ChatFormatting.GOLD);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            trusted = false; toldOnce = false; incomingSlot = null; shareIn = -1; keepOld = false; hosting = null; justOpened = null; abortIncoming();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            testOpenToLan(client);
            if (client.getSingleplayerServer() != null && !TwoWeekPhaseMod.DEV) shareIn = 60;   // we are hosting: share it in 3 seconds
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (hosting != null && ++beatTicks >= 1200) {            // once a minute: "I have this world open", for the other owner's PC
                beatTicks = 0;
                String lineage = hosting, me = client.getUser().getName();
                CompletableFuture.runAsync(() -> TwpCloud.beat(lineage, me));
            }
            if (justOpened != null && ++openedTicks % 100 == 0) {
                if (openedTicks > 1200) justOpened = null;
                else bothOpened(client);
            }
            if (shareIn < 0 || --shareIn > 0) return;
            shareIn = -1;
            IntegratedServer server = client.getSingleplayerServer();
            if (server == null || client.level == null) return;
            TwpFiles.Slot slot = TwpFiles.slotOf(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
            TwpFiles.Owners owners = slot == null ? null : slot.sealed();
            if (owners == null) return;
            hosting = owners.lineage;
            beatTicks = 1200;
            justOpened = slot;
            openedTicks = 0;
            EssentialBridge.share(client, owners);
        });
        if (TwoWeekPhaseMod.DEV && System.getProperty("twp.exitFile") != null) {    // test hook: quit cleanly when a file appears
            Path exit = Path.of(System.getProperty("twp.exitFile"));
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (++exitCheck % 20 == 0 && Files.exists(exit)) client.stop();
            });
        }
        if (TwoWeekPhaseMod.DEV && System.getProperty("twp.auto") != null) {        // test hook: after 10s in the world, go back to the title for the next step
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (!autoBusy || client.level == null || client.player == null) return;
                if (++inWorldTicks < 200 || autoStep >= System.getProperty("twp.auto").split(",").length) return;
                autoBusy = false;
                inWorldTicks = 0;
                TwoWeekPhaseMod.LOG.info("test: leaving the world to run the next step");
                client.disconnectFromWorld(Component.literal("test"));
            });
        }

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (openNext != null && (screen instanceof DisconnectedScreen || screen instanceof TitleScreen)) {
                TwpFiles.Slot slot = openNext;                  // hand-over: the old host has closed the world, so open ours
                openNext = null;
                if (System.currentTimeMillis() < openNextUntil) {
                    client.execute(() -> TwpScreen.play(client, slot, new TitleScreen()));
                    return;
                }
            }
            if (!(screen instanceof TitleScreen)) return;
            // a finished session that is not online yet (web storage): put it online now, and show how far it is
            TwpSave job = TwpSave.current;
            boolean joining = System.currentTimeMillis() < joiningUntil;
            TwpFiles.Slot unsaved = TwoWeekPhaseMod.DEV || joining || (job != null && job.running()) ? null : TwpSave.waiting();
            if (unsaved != null) {
                TwpSave started = TwpSave.start(client, unsaved);
                client.execute(() -> CompatClient.setScreen(client, new TwpSaveScreen(screen, started)));
            }
            addLibrary(client, screen, w);
            testAuto(client, screen);
        });
    }

    private static final int LIB_W = 130, LIB_TOP = 34;

    /** Shortens a text so it fits in a number of pixels. */
    static String clip(Minecraft client, String text, int pixels) {
        if (client.font.width(text) <= pixels) return text;
        while (text.length() > 1 && client.font.width(text + "...") > pixels) text = text.substring(0, text.length() - 1);
        return text + "...";
    }

    /**
     * The library: a small panel that is always on the right of the title screen. It shows the two worlds
     * played most recently (press one to play it) and a button that opens the whole library.
     */
    private static void addLibrary(Minecraft client, Screen screen, int width) {
        TwpScreen.lookOnline(client, () -> { });         // worlds that are online but not here yet show up the next time this screen opens
        List<TwpFiles.Slot> worlds = TwpFiles.slots();
        int x = width - LIB_W - 4, y = LIB_TOP + 28;
        for (int i = 0; i < Math.min(2, worlds.size()); i++) {
            TwpFiles.Slot slot = worlds.get(i);
            boolean ready = TwpScreen.playable(slot);
            String label = clip(client, (ready ? "Play: " : "Waiting: ") + slot.title(), LIB_W - 8);
            Screens.getWidgets(screen).add(Button.builder(Component.literal(label), b -> {
                if (ready) TwpScreen.play(client, slot, screen);
                else CompatClient.setScreen(client, new TwpScreen(screen));
            }).bounds(x, y, LIB_W, 20).build());
            y += 22;
        }
        boolean empty = worlds.isEmpty();
        Screens.getWidgets(screen).add(Button.builder(Component.literal(empty ? "Add a world" : "Open library (" + worlds.size() + ")"),
                b -> CompatClient.setScreen(client, empty ? new TwpAddScreen(new TwpScreen(screen)) : new TwpScreen(screen)))
                .bounds(x, y, LIB_W, 20).build());
        // If a friend is hosting a world through Essential right now, a Join button shows up here by itself.
        // This is how the other owner gets in the first time, before the world is in their own library.
        EssentialBridge.Live[] live = {null};
        Button join = Button.builder(Component.literal("Join"), b -> {
            if (live[0] != null) EssentialBridge.join(client, screen, live[0].host(), live[0].world().isBlank() ? "Two Week Phase" : live[0].world());
        }).bounds(x, y + 22, LIB_W, 20).build();
        join.visible = false;
        Screens.getWidgets(screen).add(join);
        int[] ticks = {0};
        ScreenEvents.afterTick(screen).register(s -> {
            if (ticks[0]++ % 40 != 0) return;
            List<EssentialBridge.Live> all = EssentialBridge.sessions();
            live[0] = all.isEmpty() ? null : all.get(0);
            join.visible = live[0] != null;
            if (live[0] != null) join.setMessage(Component.literal(clip(client, "Join now: " + (live[0].world().isBlank() ? "friend's world" : live[0].world()), LIB_W - 8)));
        });
        String count = empty ? "Empty" : worlds.size() == 1 ? "1 shared world" : worlds.size() + " shared worlds";
        java.util.function.Supplier<String> status = () -> {
            TwpSave job = TwpSave.current;
            if (job != null && job.running()) return "Saving online " + (job.total > 0 ? (int) (100 * job.done / job.total) : 0) + "%";
            if (job != null && System.currentTimeMillis() - job.finishedAt < 10 * 60_000) return job.ok ? "Saved online: finished" : "NOT saved online";
            return count;
        };
        String text = count;
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
            graphics.fill(x, LIB_TOP, x + LIB_W, LIB_TOP + 26, 0xB0000000);
            graphics.text(client.font, "Two Week Phase library", x + 4, LIB_TOP + 4, 0xFFFFD050);
            String now = status.get();
            graphics.text(client.font, now, x + 4, LIB_TOP + 15, now.startsWith("NOT") ? 0xFFFF6060 : now.equals(text) ? 0xFFFFFFFF : 0xFF70E070);
        });
    }

    /**
     * Both owners pressed Play at about the same time, so each is alone in their own copy. One of them has to move:
     * the owner who did NOT add the world to the library closes theirs and joins the other. (Their minute alone is
     * not counted as progress, so nothing has to be handed over afterwards.)
     */
    private void bothOpened(Minecraft client) {
        TwpFiles.Slot slot = justOpened;
        IntegratedServer server = client.getSingleplayerServer();
        if (slot == null || server == null || server.getPlayerCount() > 1) return;
        TwpFiles.Owners owners = slot.sealed();
        String me = client.getUser().getName();
        if (owners == null || !owners.players.get(1).name.equalsIgnoreCase(me)) return;   // the owner who added it stays
        EssentialBridge.Live live = EssentialBridge.liveFor(client, slot);
        if (live == null) return;
        justOpened = null;
        String friend = owners.players.get(0).name;
        TwoWeekPhaseMod.LOG.info("{} opened this world at the same time: joining them instead", friend);
        client.disconnectFromWorld(Component.literal(friend + " opened this world too. Joining " + friend + "..."));
        EssentialBridge.join(client, new TitleScreen(), live.host(), slot.title());
    }

    /**
     * Hand-over, host side: the other owner holds newer progress. Close our world and wait for theirs to open,
     * then join it. Called by the rules of the world we are hosting.
     */
    public static void yieldTo(UUID friend, String friendName) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.getSingleplayerServer() == null) return;
            client.disconnectFromWorld(Component.literal("Switching to " + friendName + "'s newer progress"));
            CompatClient.setScreen(client, new TwpWaitScreen(new TitleScreen(), friend, friendName));
        });
    }

    private static void chat(Minecraft client, String text, ChatFormatting color) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal("Two Week Phase: " + text).withStyle(color));
    }

    // ------------------------------------------------------------ receiving the world

    private void onWelcome(Minecraft client, TwpNet.Welcome payload) {
        TwpNet.WelcomeMsg m;
        try {
            m = TwpFiles.GSON.fromJson(payload.json(), TwpNet.WelcomeMsg.class);
        } catch (Exception e) {
            return;
        }
        if (m == null || m.owners == null || m.owners.players == null || m.owners.players.size() != 2 || m.owners.lineage == null) return;
        if (m.protocol != TwpNet.PROTOCOL) {
            chat(client, "you two have different versions of this mod, so the world copy cannot be sent. Both install the same file.", ChatFormatting.RED);
            return;
        }
        String me = client.getUser().getName();
        TwpFiles.Slot slot = TwpFiles.byLineage(m.owners.lineage);
        TwpFiles.Owners sealed;
        if (slot == null) {                                          // a world this PC's library does not have yet
            boolean mine = m.owners.players.stream().anyMatch(o -> o.name != null && o.name.equalsIgnoreCase(me));
            if (!mine) return;                                       // not one of the two owners: nothing to keep
            try {
                slot = TwpFiles.newSlot(m.owners.lineage);
            } catch (IllegalArgumentException e) {
                return;                                              // not a real world id
            }
            TwpFiles.write(slot.sealedFile(), m.owners);             // this PC now knows the two owners, for good
            sealed = m.owners;
            chat(client, "\"" + slot.title() + "\" belongs to " + sealed.names() + ". It is being added to your library.", ChatFormatting.GOLD);
        } else {
            sealed = slot.sealed();
            if (!sealed.sameAs(m.owners)) {
                chat(client, "this world does not match the one in your library. Your copy was not touched.", ChatFormatting.YELLOW);
                return;
            }
            boolean filled = false;                                  // learn the other owner's exact id once it is known
            for (int i = 0; i < 2; i++) {
                if (sealed.players.get(i).uuid == null && m.owners.players.get(i).uuid != null) {
                    sealed.players.get(i).uuid = m.owners.players.get(i).uuid;
                    filled = true;
                }
            }
            if (filled) TwpFiles.write(slot.sealedFile(), sealed);
        }
        trusted = true;
        incomingSlot = slot;

        TwpNet.RequestMsg r = new TwpNet.RequestMsg();
        r.lineage = sealed.lineage;
        r.haveWorld = slot.ready();
        r.yield = r.haveWorld && Files.exists(slot.yieldFlag());     // we agreed to continue from the other owner's progress
        keepOld = r.yield;
        if (r.haveWorld) {
            r.sessions = TwpFiles.state(slot.worldDir()).sessions;
            r.files = TwpFiles.manifest(slot.worldDir());
        }
        byte[] all = TwpFiles.GSON.toJson(r).getBytes(StandardCharsets.UTF_8);
        int total = Math.max(1, (all.length + TwpNet.TO_SERVER_CHUNK - 1) / TwpNet.TO_SERVER_CHUNK);
        int id = nextRequestId++;
        for (int i = 0; i < total; i++) {
            int from = i * TwpNet.TO_SERVER_CHUNK, to = Math.min(all.length, from + TwpNet.TO_SERVER_CHUNK);
            byte[] piece = new byte[to - from];
            System.arraycopy(all, from, piece, 0, piece.length);
            ClientPlayNetworking.send(new TwpNet.Part(id, i, total, piece));
        }
    }

    private void abortIncoming() {
        try { if (incomingOut != null) incomingOut.close(); } catch (IOException ignored) { }
        incomingOut = null;
        if (incomingFile != null) { try { Files.deleteIfExists(incomingFile); } catch (IOException ignored) { } }
        incomingFile = null;
        incomingId = -1;
    }

    private void onData(Minecraft client, TwpNet.Data d) {
        if (!trusted) return;
        try {
            boolean big = (long) d.total() * TwpNet.TO_CLIENT_CHUNK > 20_000_000L;   // only talk about copies that take a while
            if (d.index() == 0) {
                abortIncoming();
                Path dir = TwpFiles.dataDir().resolve("incoming");
                Files.createDirectories(dir);
                incomingFile = dir.resolve(d.id() + ".zip");
                incomingOut = Files.newOutputStream(incomingFile);
                incomingId = d.id();
                incomingNext = 0;
                if (big) chat(client, "receiving your copy of the world (about " + ((long) d.total() * TwpNet.TO_CLIENT_CHUNK >> 20)
                        + " MB). Stay in the game until it says the copy is saved.", ChatFormatting.GOLD);
            }
            if (d.id() != incomingId || d.index() != incomingNext || incomingOut == null) { abortIncoming(); return; }
            incomingOut.write(d.bytes());
            incomingNext++;
            ClientPlayNetworking.send(new TwpNet.Ack(d.id(), d.index()));   // the host sends the next pieces only after this
            int quarter = d.total() / 4;
            if (big && quarter > 0 && incomingNext % quarter == 0 && incomingNext < d.total()) {
                chat(client, "receiving the world: " + (incomingNext / quarter * 25) + "%", ChatFormatting.GRAY);
            }
        } catch (IOException e) {
            TwoWeekPhaseMod.LOG.error("Receiving the world copy failed", e);
            abortIncoming();
        }
    }

    private void onDone(Minecraft client, TwpNet.Done payload) {
        if (!trusted || incomingOut == null) return;
        TwpNet.DoneMsg d;
        try {
            d = TwpFiles.GSON.fromJson(payload.json(), TwpNet.DoneMsg.class);
            incomingOut.close();
        } catch (Exception e) {
            abortIncoming();
            return;
        }
        incomingOut = null;
        Path zip = incomingFile;
        incomingFile = null;
        TwpFiles.Slot slot = incomingSlot;
        if (d == null || d.id != incomingId || slot == null) { abortIncoming(); return; }
        incomingId = -1;
        boolean keep = keepOld;
        CompletableFuture.supplyAsync(() -> apply(zip, d, slot, keep)).whenComplete((ok, err) -> client.execute(() -> {
            if (err != null || !Boolean.TRUE.equals(ok)) {
                TwoWeekPhaseMod.LOG.error("Could not save the received world copy", err);
                chat(client, "the copy of the world could not be saved. It will be tried again.", ChatFormatting.RED);
            } else if (keep) {
                keepOld = false;
                toldOnce = true;
                chat(client, "your world now has the other owner's newer progress. What it replaced on this PC is kept in the mod's \"replaced\" folder.", ChatFormatting.GREEN);
            } else if (!toldOnce) {
                toldOnce = true;
                chat(client, "your own copy of the world is saved and up to date. It is in your library now and stays up to date while you play together.", ChatFormatting.GREEN);
            }
        }));
    }

    /** Puts the received files into this PC's copy of the world. */
    /**
     * Puts the received files into this PC's copy of the world. With keepOld, every file that is replaced or
     * removed is first moved into the world's "replaced" folder.
     */
    private static boolean apply(Path zip, TwpNet.DoneMsg d, TwpFiles.Slot slot, boolean keepOld) {
        try {
            if (Files.size(zip) != d.size) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(zip)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            if (!HexFormat.of().formatHex(md.digest()).equals(d.sha1)) return false;

            Path world = slot.worldDir().toAbsolutePath().normalize();
            Files.createDirectories(world);
            Files.writeString(slot.syncingFlag(), "receiving");       // if we are interrupted, the copy is marked incomplete
            Path keepDir = slot.replacedDir().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
            TwpNet.TransferManifest tm;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                ZipEntry me = z.getEntry(TwpNet.MANIFEST_ENTRY);
                if (me == null) return false;
                tm = TwpFiles.GSON.fromJson(new String(z.getInputStream(me).readAllBytes(), StandardCharsets.UTF_8), TwpNet.TransferManifest.class);
                if (tm == null || tm.files == null) return false;
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || e.getName().equals(TwpNet.MANIFEST_ENTRY)) continue;
                    Path target = world.resolve(e.getName()).normalize();
                    if (!target.startsWith(world)) return false;      // never write outside the world folder
                    Files.createDirectories(target.getParent());
                    if (keepOld) TwpFiles.setAside(world, target, keepDir);
                    try (var in = z.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            for (String path : TwpFiles.manifest(world).keySet()) {   // files the host no longer has
                if (tm.files.containsKey(path)) continue;
                if (keepOld) TwpFiles.setAside(world, world.resolve(path), keepDir);
                else Files.deleteIfExists(world.resolve(path));
            }
            for (Map.Entry<String, long[]> e : tm.files.entrySet()) { // same timestamps as the host, so next time only real changes travel
                Path f = world.resolve(e.getKey());
                if (Files.exists(f)) TwpFiles.setTime(f, e.getValue()[1]);
            }
            Files.deleteIfExists(slot.syncingFlag());
            if (keepOld) {
                Files.deleteIfExists(slot.yieldFlag());               // done: this copy and the other owner's are the same again
                TwpFiles.pruneReplaced(slot);
            }
            Files.deleteIfExists(zip);
            TwoWeekPhaseMod.LOG.info("World copy received: {} file(s) in this PC's copy now", tm.files.size());
            return true;
        } catch (Exception e) {
            TwoWeekPhaseMod.LOG.error("Applying the world copy failed", e);
            return false;
        }
    }

    // ------------------------------------------------------------ test hooks (only when run from the development environment)

    private static void testAuto(Minecraft client, Screen title) {
        String all = TwoWeekPhaseMod.DEV ? System.getProperty("twp.auto") : null;
        if (all == null || autoBusy) return;
        String[] steps = all.split(",");                 // e.g. "create:Bob,play" = create the world, then reopen it
        if (autoStep >= steps.length) return;
        String auto = steps[autoStep++];
        autoBusy = true;
        inWorldTicks = 0;
        TwoWeekPhaseMod.LOG.info("test: running step '{}'", auto);
        client.execute(() -> {
            List<TwpFiles.Slot> have = TwpFiles.slots();
            if (auto.startsWith("create:")) {
                if (!have.isEmpty() && have.get(0).ready()) TwpScreen.play(client, have.get(0), title);
                else TwoWeekPhaseMod.LOG.info("auto create -> {}", TwpScreen.create(client, auto.substring(7), title));
            } else if (auto.equals("play")) {
                if (!have.isEmpty()) TwpScreen.play(client, have.get(0), title);
            } else if (auto.startsWith("join:")) {
                String address = auto.substring(5);
                ConnectScreen.startConnecting(title, client, ServerAddress.parseString(address), new ServerData("twp", address, ServerData.Type.OTHER), false, null);
            }
        });
    }

    private static void testOpenToLan(Minecraft client) {
        String port = TwoWeekPhaseMod.DEV ? System.getProperty("twp.lanPort") : null;
        IntegratedServer server = client.getSingleplayerServer();
        if (port == null || server == null || server.isPublished()) return;
        if ("true".equals(System.getProperty("twp.offline"))) server.setUsesAuthentication(false);
        boolean ok = CompatClient.publishLan(server, Integer.parseInt(port));
        TwoWeekPhaseMod.LOG.info("test: opened to LAN on port {}: {}", port, ok);
    }
}
