package net.husein.twp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;

/** The few client-side calls that are named differently in Minecraft 26.3. */
public final class CompatClient {
    private CompatClient() { }

    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    /** test hook: open the world to the local network */
    public static boolean publishLan(IntegratedServer server, int port) {
        return server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port);
    }

    /** 26.3 keeps "may guests use commands" on the hosting game itself. */
    public static void forbidGuestCheats(MinecraftServer server) {
        if (server instanceof IntegratedServer host && host.getGuestCommandAccess()) host.setGuestCommandAccess(false);
    }
}
