package net.husein.twp;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The rules of the Two Week Phase world. They run on whichever owner's game is hosting it:
 * only the two owners may be in it, nobody has cheats, the dead stay ghosts, and the other
 * owner's game is handed an up-to-date copy of the world.
 */
public class TwoWeekPhaseMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("twoweekphase");
    public static final boolean DEV = FabricLoader.getInstance().isDevelopmentEnvironment();
    private static final boolean CLIENT = FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
    private static final String ASIDE = ".twp-real";

    private boolean active;
    private TwpFiles.Slot slot;
    private Path world;
    private TwpFiles.Owners owners;
    private TwpFiles.State state;
    private boolean sessionCounted;
    private int tick;
    private int nextId = 1;
    private int savingHold;
    private int testKillAt = -1;
    private boolean testKillDone;
    /** the world that is being closed, until it has been put online */
    private TwpFiles.Slot closing;
    private String closingBy;
    /** what happened the last time a world was put online, for the library to show */
    public static volatile String cloudNote;
    public static volatile long cloudNoteAt;
    /** which owner (0 or 1) is hosting right now */
    private int hostIdx = -1;
    /** the guest whose newer progress the host has been offered (see /twp usetheirs) */
    private UUID offered;
    private boolean handingOver;
    private int handOverAt = -1;
    private UUID handOverTo;
    private String handOverName;

    private static final class Assembler { int id; byte[][] parts; int got; }

    private static final class Outgoing {
        int id; Path file; RandomAccessFile raf; int total; int next; long size; String sha1; Map<String, long[]> manifest;
        int acked;      // pieces the guest has confirmed
        int heardAt;    // tick of the guest's last confirmation
        void close() {
            try { if (raf != null) raf.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
    }

    private final Map<UUID, Assembler> incoming = new HashMap<>();
    private final Map<UUID, Outgoing> outgoing = new HashMap<>();
    /** what each guest's copy of the world holds, as far as we know */
    private final Map<UUID, Map<String, long[]>> known = new HashMap<>();
    private final Map<UUID, Integer> lastSync = new HashMap<>();
    private final Set<UUID> building = new HashSet<>();

    @Override
    public void onInitialize() {
        TwpNet.registerTypes();
        ServerLifecycleEvents.SERVER_STARTED.register(this::onStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (active) restoreAsideFiles(server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
            closing = active && CLIENT ? slot : null;
            closingBy = hostIdx >= 0 ? owners.players.get(hostIdx).name : null;
            active = false;
            outgoing.values().forEach(Outgoing::close);
            outgoing.clear(); incoming.clear(); known.clear(); lastSync.clear(); building.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // the world is fully saved and closed: put this session online (only the owner who hosted it does this)
            TwpFiles.Slot done = closing;
            closing = null;
            if (done == null || closingBy == null || owners == null) return;
            if (Files.exists(done.yieldFlag())) return;    // this session is being given up for the other owner's newer progress
            TwpCloud.Store st = TwpCloud.store();
            // Web storage: the game shows a "saving online" screen with progress once we are back at the title (see the client).
            // A synced folder: copying into it takes seconds, so it is done right here.
            if (st == null || st.exact()) return;
            String note = TwpCloud.push(done, owners, closingBy, (sent, total) -> { });
            if (note != null) {
                cloudNote = note;
                cloudNoteAt = System.currentTimeMillis();
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            Outgoing o = outgoing.remove(id);
            if (o != null) o.close();
            incoming.remove(id); known.remove(id); lastSync.remove(id);
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!active || !(entity instanceof ServerPlayer p)) return;
            int idx = ownerIndex(p.level().getServer(), p.nameAndId());
            if (idx >= 0 && !state.dead.contains(idx)) {
                state.dead.add(idx);
                saveState();
                LOG.info("{} died. They are a ghost in this world from now on.", p.getName().getString());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(TwpNet.Part.TYPE, (payload, context) ->
                context.server().execute(() -> onPart(context.server(), context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(TwpNet.Ack.TYPE, (payload, context) -> context.server().execute(() -> {
            Outgoing o = outgoing.get(context.player().getUUID());
            if (o == null || o.id != payload.id()) return;
            o.acked = Math.max(o.acked, Math.min(o.next, payload.index() + 1));
            o.heardAt = tick;
        }));
        // "/twp usetheirs": the host decides to continue with the other owner's version when both played separately
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
                Commands.literal("twp").then(Commands.literal("usetheirs").executes(c -> {
                    MinecraftServer server = c.getSource().getServer();
                    ServerPlayer who = c.getSource().getPlayer();
                    if (!active || who == null || !server.isSingleplayerOwner(who.nameAndId())) {
                        c.getSource().sendFailure(Component.literal("Only the owner who opened the world can use this."));
                        return 0;
                    }
                    ServerPlayer guest = offered == null ? null : server.getPlayerList().getPlayer(offered);
                    if (guest == null) {
                        c.getSource().sendFailure(Component.literal("There is no other version to switch to right now."));
                        return 0;
                    }
                    handOver(server, guest);
                    return 1;
                }))));
    }

    // ------------------------------------------------------------ the world starts

    private void onStart(MinecraftServer server) {
        active = false;
        sessionCounted = false;
        testKillAt = -1;
        testKillDone = false;
        tick = 0;
        hostIdx = -1;
        offered = null;
        handingOver = false;
        handOverAt = -1;
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        slot = TwpFiles.slotOf(root);
        if (slot == null) return;                          // some other world: not our business
        world = root;
        TwpFiles.Owners sealed = slot.sealed();
        TwpFiles.Owners inWorld = TwpFiles.read(world.resolve(TwpFiles.OWNERS_IN_WORLD), TwpFiles.Owners.class);
        if (sealed == null && inWorld != null && inWorld.players != null && inWorld.players.size() == 2) {
            sealed = inWorld;                              // this PC lost its record: take it back from the world
            TwpFiles.write(slot.sealedFile(), sealed);
        }
        if (sealed == null) {
            LOG.error("The Two Week Phase world has no owners record. It will run without the two-owner rules.");
            return;
        }
        if (inWorld != null && !sealed.sameAs(inWorld)) {
            LOG.warn("The owners record inside the world had been changed. It has been put back: {} own this world.", sealed.names());
        }
        TwpFiles.write(world.resolve(TwpFiles.OWNERS_IN_WORLD), sealed);
        owners = sealed;
        state = TwpFiles.state(world);
        try {
            Files.deleteIfExists(slot.yieldFlag());       // this PC is opening the world itself again: its copy counts again
        } catch (IOException ignored) {
        }
        if (CLIENT) TwpCloud.markOpened(slot);            // from now on this copy holds progress that is not online yet
        active = true;
        LOG.info("Two Week Phase world opened. Owners: {}. Hardcore: {}.", owners.names(), server.isHardcore());
        giveHostTheirOwnData(server);
    }

    /** Puts back any player file that was set aside (see giveHostTheirOwnData), e.g. after a crash. */
    private static void restoreAsideFiles(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path aside : s.filter(p -> p.getFileName().toString().endsWith(ASIDE)).toList()) {
                String name = aside.getFileName().toString();
                Files.move(aside, dir.resolve(name.substring(0, name.length() - ASIDE.length())), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Could not restore player data in {}", dir, e);
        }
    }

    /**
     * The world file remembers ONE player as "the single-player owner", and the game loads that player's
     * saved data for whoever is hosting. So when the other owner opens their copy, they would wake up with
     * the first owner's inventory and position. Until the host has joined, we put the host's own saved data
     * where the game looks, and keep the other owner's real data safely aside.
     */
    private void giveHostTheirOwnData(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            restoreAsideFiles(dir);
            GameProfile host = server.getSingleplayerProfile();
            UUID stored = server.getWorldData().getSinglePlayerUUID();
            if (host == null || stored == null || stored.equals(host.id())) return;   // same owner as last time: nothing to do
            Path storedFile = dir.resolve(stored + ".dat");
            Path mine = dir.resolve(host.id() + ".dat");
            if (!Files.exists(storedFile)) return;
            Files.move(storedFile, dir.resolve(stored + ".dat" + ASIDE), StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(mine)) {
                Files.copy(mine, storedFile);
                LOG.info("{} is hosting: their own inventory and position were loaded.", host.name());
            } else {
                LOG.warn("{} is hosting but has never been in this world before: they start fresh.", host.name());
            }
        } catch (Exception e) {
            LOG.error("Could not load the host's own player data", e);
        }
    }

    private void saveState() {
        TwpFiles.write(world.resolve(TwpFiles.STATE_IN_WORLD), state);
    }

    /** 0 or 1 for the two owners, -1 for anyone else. */
    private int ownerIndex(MinecraftServer server, NameAndId who) {
        String uuid = who.id().toString();
        for (int i = 0; i < 2; i++) {
            if (uuid.equals(owners.players.get(i).uuid)) return i;
        }
        for (int i = 0; i < 2; i++) {
            TwpFiles.Owner o = owners.players.get(i);
            // An owner we have not met yet is recognised by name. Without official accounts ids are not reliable, so names decide.
            if ((o.uuid == null || !server.usesAuthentication()) && o.name.equalsIgnoreCase(who.name())) return i;
        }
        return -1;
    }

    private void onJoin(MinecraftServer server, ServerPlayer p) {
        if (!active) return;
        NameAndId who = p.nameAndId();
        boolean hosting = server.isSingleplayerOwner(who);
        if (hosting) {
            // the host has loaded their data now: put the other owner's real file back where it belongs
            restoreAsideFiles(server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
        }
        int idx = ownerIndex(server, who);
        if (idx < 0) {
            p.connection.disconnect(Component.literal("This world belongs to " + owners.names() + "."));
            return;
        }
        TwpFiles.Owner o = owners.players.get(idx);
        if (o.uuid == null) {                              // first time we see this owner: remember exactly who they are
            o.uuid = who.id().toString();
            TwpFiles.write(slot.sealedFile(), owners);
            TwpFiles.write(world.resolve(TwpFiles.OWNERS_IN_WORLD), owners);
        }
        LOG.info("{} entered the world at {}{}", who.name(), p.blockPosition().toShortString(), hosting ? " (hosting)" : "");
        if (DEV && who.name().equalsIgnoreCase(System.getProperty("twp.kill", "")) && testKillAt < 0 && !testKillDone) testKillAt = tick + 240;
        if (hosting) {
            hostIdx = idx;                                 // (the session is counted once it has lasted two minutes: see onTick)
            return;
        }
        if (ServerPlayNetworking.canSend(p, TwpNet.Welcome.TYPE)) {
            TwpNet.WelcomeMsg m = new TwpNet.WelcomeMsg();
            m.owners = owners;
            m.sessions = state.sessions;
            ServerPlayNetworking.send(p, new TwpNet.Welcome(TwpFiles.GSON.toJson(m)));
        } else {
            p.sendSystemMessage(Component.literal("Two Week Phase: " + who.name() + " does not have the mod installed, so their game cannot keep a copy of the world.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    // ------------------------------------------------------------ every tick

    private int syncTicks() {
        String s = DEV ? System.getProperty("twp.syncSeconds") : null;
        return s != null ? Math.max(40, Integer.parseInt(s) * 20) : 6000;   // every 5 minutes
    }

    private void onTick(MinecraftServer server) {
        if (!active) return;
        tick++;

        // A session only counts as "this owner played" after two minutes. Opening the world for a moment
        // (for example when both owners press Play at once and one of them then joins the other) is not progress.
        if (!sessionCounted && hostIdx >= 0 && tick >= 2400) {
            sessionCounted = true;
            state.sessions[hostIdx]++;
            saveState();
        }

        if (handOverAt >= 0 && tick >= handOverAt) {                  // the hand-over was announced 5 seconds ago: go
            handOverAt = -1;
            if (CLIENT) net.husein.twp.client.TwoWeekPhaseClient.yieldTo(handOverTo, handOverName);
            return;
        }

        if (testKillAt >= 0 && tick >= testKillAt) {                  // test hook (development environment only)
            testKillAt = -1;
            testKillDone = true;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(System.getProperty("twp.kill", ""))) p.kill(p.level());
            }
        }

        if (tick % 20 == 0) {
            CompatServer.forbidCheats(server);                             // however the world was opened to others, no cheats
            if (CLIENT) net.husein.twp.client.CompatClient.forbidGuestCheats(server);
            for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                int idx = ownerIndex(server, p.nameAndId());
                if (idx < 0) {
                    p.connection.disconnect(Component.literal("This world belongs to " + owners.names() + "."));
                    continue;
                }
                if (!p.isAlive()) continue;                           // on the death screen: let the game finish that first
                GameType expected = state.dead.contains(idx) ? GameType.SPECTATOR : GameType.SURVIVAL;
                if (p.gameMode() != expected) {
                    p.setGameMode(expected);
                    LOG.info("{} was put in {} mode (the only mode allowed for them here).", p.getName().getString(), expected.getName());
                }
            }
            int every = syncTicks();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                UUID id = p.getUUID();
                if (known.containsKey(id) && tick - lastSync.getOrDefault(id, tick) >= every) startSync(server, p);
            }
        }

        // feed the transfers a little every tick, so the game stays smooth
        for (UUID id : new ArrayList<>(outgoing.keySet())) {
            Outgoing o = outgoing.get(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) { o.close(); outgoing.remove(id); continue; }
            if (tick - o.heardAt > 1200) {                            // a minute of silence: give up, the next sync starts over
                LOG.warn("{} stopped confirming the world copy. It will be sent again later.", p.getName().getString());
                o.close();
                outgoing.remove(id);
                continue;
            }
            try {
                // only a few pieces travel at once, so a big world never chokes the connection the game itself needs
                for (int n = 0; n < TwpNet.WINDOW && o.next < o.total && o.next - o.acked < TwpNet.WINDOW; n++) {
                    byte[] buf = new byte[(int) Math.min(TwpNet.TO_CLIENT_CHUNK, o.size - (long) o.next * TwpNet.TO_CLIENT_CHUNK)];
                    o.raf.seek((long) o.next * TwpNet.TO_CLIENT_CHUNK);
                    o.raf.readFully(buf);
                    ServerPlayNetworking.send(p, new TwpNet.Data(o.id, o.next, o.total, buf));
                    o.next++;
                }
                if (o.next >= o.total) {
                    TwpNet.DoneMsg d = new TwpNet.DoneMsg();
                    d.id = o.id; d.size = o.size; d.sha1 = o.sha1;
                    ServerPlayNetworking.send(p, new TwpNet.Done(TwpFiles.GSON.toJson(d)));
                    known.put(id, o.manifest);
                    o.close();
                    outgoing.remove(id);
                }
            } catch (IOException e) {
                LOG.error("Sending the world copy failed", e);
                o.close();
                outgoing.remove(id);
            }
        }
    }

    // ------------------------------------------------------------ giving the other owner a copy

    private void onPart(MinecraftServer server, ServerPlayer p, TwpNet.Part part) {
        if (!active || ownerIndex(server, p.nameAndId()) < 0) return;
        if (part.total() < 1 || part.total() > 400 || part.index() < 0 || part.index() >= part.total()) return;
        Assembler a = incoming.get(p.getUUID());
        if (a == null || a.id != part.id() || a.parts.length != part.total()) {
            a = new Assembler();
            a.id = part.id();
            a.parts = new byte[part.total()][];
            incoming.put(p.getUUID(), a);
        }
        if (a.parts[part.index()] == null) a.got++;
        a.parts[part.index()] = part.bytes();
        if (a.got < a.parts.length) return;
        incoming.remove(p.getUUID());
        int len = 0;
        for (byte[] b : a.parts) len += b.length;
        byte[] all = new byte[len];
        int at = 0;
        for (byte[] b : a.parts) { System.arraycopy(b, 0, all, at, b.length); at += b.length; }
        TwpNet.RequestMsg r;
        try {
            r = TwpFiles.GSON.fromJson(new String(all, StandardCharsets.UTF_8), TwpNet.RequestMsg.class);
        } catch (Exception e) {
            return;
        }
        if (r != null) onRequest(server, p, r);
    }

    private void onRequest(MinecraftServer server, ServerPlayer p, TwpNet.RequestMsg r) {
        String name = p.getName().getString();
        if (r.lineage != null && !r.lineage.equals(owners.lineage)) {
            p.sendSystemMessage(Component.literal("Two Week Phase: your mod holds a different Two Week Phase world. It was not touched.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        int[] theirs = r.sessions != null && r.sessions.length == 2 ? r.sessions : new int[]{0, 0};
        if (r.haveWorld && !r.yield && !TwpFiles.atLeast(state.sessions, theirs)) {
            // Their copy contains play that the world being hosted now does not have. It is never overwritten here.
            int g = ownerIndex(server, p.nameAndId());
            String host = hostIdx >= 0 ? owners.players.get(hostIdx).name : "the host";
            boolean theyPlayedAlone = g >= 0 && theirs[g] > state.sessions[g];
            // the host's own counter may already include the session running right now: beyond that one, is there more they have not seen?
            boolean hostPlayedAloneToo = hostIdx >= 0 && state.sessions[hostIdx] - (sessionCounted ? 1 : 0) > theirs[hostIdx];
            if (theyPlayedAlone && !hostPlayedAloneToo && tick < 18000) {
                handOver(server, p);                       // only they have new progress: continue from theirs, by itself
                return;
            }
            offered = theyPlayedAlone ? p.getUUID() : null;
            String msg = theyPlayedAlone
                    ? name + " and " + host + " have both played this world separately, so the two versions are different. Nothing was overwritten. "
                    + "To continue with " + name + "'s version, " + host + " types /twp usetheirs (" + host + "'s version is kept as a backup). To continue with "
                    + host + "'s, just keep playing: " + name + "'s version stays on their PC."
                    : "The two copies of this world are different. Neither was overwritten.";
            server.getPlayerList().broadcastSystemMessage(Component.literal("Two Week Phase: " + msg).withStyle(ChatFormatting.RED), false);
            return;
        }
        known.put(p.getUUID(), r.haveWorld && r.files != null ? r.files : new HashMap<>());
        startSync(server, p);
    }

    /**
     * The guest holds newer progress than the world being hosted. A running world cannot be swapped for another,
     * so the roles swap instead: the guest's game opens the guest's copy, the host's game joins it, and the normal
     * copy-to-the-guest then brings the host's PC up to date (keeping what it replaces).
     */
    private void handOver(MinecraftServer server, ServerPlayer guest) {
        if (handingOver || !ServerPlayNetworking.canSend(guest, TwpNet.Swap.TYPE)) return;
        try {
            Files.writeString(slot.yieldFlag(), "yield");
        } catch (IOException e) {
            LOG.error("Could not prepare the hand-over", e);
            return;
        }
        handingOver = true;
        String name = guest.getName().getString();
        LOG.info("{} has newer progress: handing the world over to their copy.", name);
        server.getPlayerList().broadcastSystemMessage(Component.literal("Two Week Phase: " + name + " has newer progress. Switching to it: in a few seconds the world reopens on "
                + name + "'s side and the other owner joins there. What gets replaced is kept as a backup.").withStyle(ChatFormatting.GOLD), false);
        ServerPlayNetworking.send(guest, new TwpNet.Swap(owners.lineage));
        handOverTo = guest.getUUID();
        handOverName = name;
        handOverAt = tick + 100;
    }

    private void startSync(MinecraftServer server, ServerPlayer p) {
        UUID id = p.getUUID();
        if (outgoing.containsKey(id) || building.contains(id)) return;
        Map<String, long[]> has = new HashMap<>(known.getOrDefault(id, Map.of()));
        building.add(id);
        lastSync.put(id, tick);
        server.saveEverything(true, true, true);                       // everything on disk first...
        if (savingHold++ == 0) for (ServerLevel w : server.getAllLevels()) w.noSave = true;   // ...then hold still while we copy
        int tid = nextId++;
        Path root = world;
        int[] sessions = state.sessions.clone();
        CompletableFuture.supplyAsync(() -> buildZip(root, has, tid, sessions)).whenComplete((out, err) -> server.execute(() -> {
            if (--savingHold == 0) for (ServerLevel w : server.getAllLevels()) w.noSave = false;
            building.remove(id);
            if (err != null) { LOG.error("Could not prepare the world copy", err); return; }
            if (out == null) return;                                   // the other owner's copy is already identical
            if (!active || server.getPlayerList().getPlayer(id) == null) { out.close(); return; }
            out.heardAt = tick;
            outgoing.put(id, out);
        }));
    }

    private static boolean same(long[] a, long[] b) {
        return a != null && b != null && a.length == 2 && b.length == 2 && a[0] == b[0] && a[1] == b[1];
    }

    /** Zips only the files the other owner's copy is missing or has an older version of. */
    private static Outgoing buildZip(Path root, Map<String, long[]> has, int tid, int[] sessions) {
        try {
            Map<String, long[]> mine = TwpFiles.manifest(root);
            mine.keySet().removeIf(k -> k.endsWith(ASIDE));            // a file we set aside for a moment is not part of the world
            List<String> changed = new ArrayList<>();
            for (Map.Entry<String, long[]> e : mine.entrySet()) {
                if (!same(has.get(e.getKey()), e.getValue())) changed.add(e.getKey());
            }
            boolean removed = false;
            for (String k : has.keySet()) if (!mine.containsKey(k)) { removed = true; break; }
            if (changed.isEmpty() && !removed) return null;

            Path dir = TwpFiles.dataDir().resolve("outgoing");
            Files.createDirectories(dir);
            Path zip = dir.resolve(tid + ".zip");
            try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
                z.setLevel(1);
                for (String path : changed) {
                    try {
                        z.putNextEntry(new ZipEntry(path));
                        Files.copy(root.resolve(path), z);
                        z.closeEntry();
                    } catch (IOException e) {
                        mine.remove(path);                             // vanished while copying: it will go next time
                    }
                }
                TwpNet.TransferManifest m = new TwpNet.TransferManifest();
                m.sessions = sessions;
                m.files = mine;
                z.putNextEntry(new ZipEntry(TwpNet.MANIFEST_ENTRY));
                z.write(TwpFiles.GSON.toJson(m).getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            Outgoing o = new Outgoing();
            o.id = tid;
            o.file = zip;
            o.size = Files.size(zip);
            o.total = (int) Math.max(1, (o.size + TwpNet.TO_CLIENT_CHUNK - 1) / TwpNet.TO_CLIENT_CHUNK);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(zip)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            o.sha1 = HexFormat.of().formatHex(md.digest());
            o.raf = new RandomAccessFile(zip.toFile(), "r");
            o.manifest = mine;
            return o;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
