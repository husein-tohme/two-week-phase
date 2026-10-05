package net.husein.twp;

import net.minecraft.server.MinecraftServer;

/** The few server-side calls that are named differently in Minecraft 26.1. */
public final class CompatServer {
    private CompatServer() { }

    /** However the world was opened to others, nobody gets cheats. */
    public static void forbidCheats(MinecraftServer server) {
        server.getPlayerList().setAllowCommandsForAllPlayers(false);
    }
}
