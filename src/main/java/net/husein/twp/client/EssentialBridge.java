package net.husein.twp.client;

import com.google.gson.JsonObject;
import net.husein.twp.TwoWeekPhaseMod;
import net.husein.twp.TwpFiles;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Essential's own "Host World" list only knows the worlds in the saves folder, so a library world never shows
 * up there. This does the same two steps Essential does after you pick a world and a friend: start hosting,
 * and invite the other owner. It runs by itself a moment after one of the owners opens a library world.
 * If Essential is not installed, or changes how it works, nothing breaks: the player is told what to press instead.
 */
final class EssentialBridge {
    private EssentialBridge() { }

    static void share(Minecraft client, TwpFiles.Owners owners) {
        Class<?> essential;
        try {
            essential = Class.forName("gg.essential.Essential");
        } catch (Throwable t) {
            say(client, "to play together, press Esc and open this world to LAN (the Essential mod is not installed).", ChatFormatting.GRAY);
            return;
        }
        String me = client.getUser().getName();
        TwpFiles.Owner other = owners.players.get(0).name.equalsIgnoreCase(me) ? owners.players.get(1) : owners.players.get(0);
        CompletableFuture.supplyAsync(() -> other.uuid != null ? UUID.fromString(other.uuid) : lookup(other.name))
                .whenComplete((id, err) -> client.execute(() -> start(client, essential, other.name, err == null ? id : null)));
    }

    @SuppressWarnings("unchecked")
    private static void start(Minecraft client, Class<?> essential, String friendName, UUID friend) {
        if (client.getSingleplayerServer() == null || client.level == null) return;   // the world was closed meanwhile
        try {
            Object sps = sps();
            Class<?> c = sps.getClass();
            if (c.getMethod("getLocalSession").invoke(sps) == null) {
                Class<?> source = Class.forName("gg.essential.network.connectionmanager.sps.SPSSessionSource");
                Object how = null;
                for (Object o : source.getEnumConstants()) if (((Enum<?>) o).name().equals("PAUSE_MENU")) how = o;
                c.getMethod("startLocalSession", source).invoke(sps, how);
            }
            Set<UUID> invited = new HashSet<>((Set<UUID>) c.getMethod("getInvitedUsers").invoke(sps));
            if (friend != null) invited.add(friend);
            c.getMethod("updateInvitedUsers", Set.class).invoke(sps, invited);
            if (friend != null) {
                TwoWeekPhaseMod.LOG.info("Shared through Essential and invited {} ({})", friendName, friend);
                say(client, "this world is shared and " + friendName + " is invited. He can join you from Essential now.", ChatFormatting.GREEN);
            } else {
                say(client, "this world is shared, but " + friendName + " could not be looked up. Press Esc and invite him with Essential.", ChatFormatting.YELLOW);
            }
        } catch (Throwable t) {
            TwoWeekPhaseMod.LOG.warn("Could not share the world through Essential", t);
            say(client, "could not share this world by itself. Press Esc and invite " + friendName + " with Essential.", ChatFormatting.YELLOW);
        }
    }

    /** A world someone is hosting through Essential right now, that this player can see. */
    record Live(UUID host, String world) { }

    private static Object sps() throws Exception {
        Class<?> essential = Class.forName("gg.essential.Essential");
        Object instance = essential.getMethod("getInstance").invoke(null);
        Object connection = instance.getClass().getMethod("getConnectionManager").invoke(instance);
        return connection.getClass().getMethod("getSpsManager").invoke(connection);
    }

    /** Every world Essential says a friend is hosting right now. Empty without Essential. Reads nothing from the internet. */
    static List<Live> sessions() {
        List<Live> out = new ArrayList<>();
        try {
            Object sps = sps();
            Field f = sps.getClass().getDeclaredField("remoteSessions");
            f.setAccessible(true);
            for (Object s : new ArrayList<>(((Map<?, ?>) f.get(sps)).values())) {
                UUID host = (UUID) s.getClass().getMethod("getHostUUID").invoke(s);
                Object world = s.getClass().getMethod("getWorldName").invoke(s);
                if (host != null) out.add(new Live(host, world == null ? "" : world.toString()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** The other owner hosting THIS library world right now, or null. */
    static Live liveFor(Minecraft client, TwpFiles.Slot slot) {
        TwpFiles.Owners o = slot.sealed();
        if (o == null) return null;
        String me = client.getUser().getName();
        TwpFiles.Owner other = o.players.get(0).name.equalsIgnoreCase(me) ? o.players.get(1) : o.players.get(0);
        String title = slot.title();
        for (Live s : sessions()) {
            if (other.uuid != null && !other.uuid.equalsIgnoreCase(s.host().toString())) continue;
            if (s.world().equalsIgnoreCase(title)) return s;
        }
        return null;
    }

    /** Joins a world hosted through Essential, the way Essential's own Join button does. Call on the game's main thread. */
    static void join(Minecraft client, Screen back, UUID host, String name) {
        TwoWeekPhaseClient.joiningUntil = System.currentTimeMillis() + 60_000;
        String address = address(host);
        TwoWeekPhaseMod.LOG.info("Joining {} at {}", name, address);
        try {
            Class<?> utils = Class.forName("gg.essential.util.MinecraftUtils");
            utils.getMethod("connectToServer", String.class, String.class).invoke(utils.getField("INSTANCE").get(null), name, address);
        } catch (Throwable t) {
            ConnectScreen.startConnecting(back, client, ServerAddress.parseString(address), new ServerData(name, address, ServerData.Type.OTHER), false, null);
        }
    }

    /** True if Essential is installed. */
    static boolean present() {
        try {
            Class.forName("gg.essential.Essential");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True once this friend is hosting a world through Essential that we are invited to. */
    static boolean isHosting(UUID friend) {
        try {
            Class<?> essential = Class.forName("gg.essential.Essential");
            Object instance = essential.getMethod("getInstance").invoke(null);
            Object connection = instance.getClass().getMethod("getConnectionManager").invoke(instance);
            Object sps = connection.getClass().getMethod("getSpsManager").invoke(connection);
            return sps.getClass().getMethod("getRemoteSession", UUID.class).invoke(sps, friend) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The address Essential itself uses to join a friend's hosted world. */
    static String address(UUID friend) {
        return friend + ".essential-sps";
    }

    /** The official id of a Minecraft account, from Mojang's public name lookup. Null if it cannot be found. */
    static UUID lookup(String name) {
        if (name == null || !name.matches("^[A-Za-z0-9_]{1,16}$")) return null;
        UUID known = LOOKED_UP.get(name.toLowerCase());
        if (known != null) return known;
        for (int attempt = 0; attempt < 2; attempt++) {              // the lookup service sometimes says "too many requests"
            UUID id = lookupOnce(name);
            if (id != null) {
                LOOKED_UP.put(name.toLowerCase(), id);
                return id;
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        }
        return null;
    }

    private static final Map<String, UUID> LOOKED_UP = new java.util.concurrent.ConcurrentHashMap<>();

    private static UUID lookupOnce(String name) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            String id = TwpFiles.GSON.fromJson(r.body(), JsonObject.class).get("id").getAsString();
            return UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void say(Minecraft client, String text, ChatFormatting color) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal("Two Week Phase: " + text).withStyle(color));
    }
}
