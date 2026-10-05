package net.husein.twp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;

/** The few client-side calls that are named differently in Minecraft 26.1. */
public final class CompatClient {
    private CompatClient() { }

    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    /** test hook: open the world to the local network */
    public static boolean publishLan(IntegratedServer server, int port) {
        return server.publishServer(GameType.SURVIVAL, false, port);
    }

    public static void forbidGuestCheats(MinecraftServer server) { }
}
