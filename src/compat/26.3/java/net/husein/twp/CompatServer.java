package net.husein.twp;

import net.minecraft.server.MinecraftServer;

/** The few server-side calls that are named differently in Minecraft 26.3. */
public final class CompatServer {
    private CompatServer() { }

    /** However the world was opened to others, nobody gets cheats (26.3 keeps this setting in the world itself). */
    public static void forbidCheats(MinecraftServer server) {
        if (server.getWorldData().isAllowCommands()) server.getWorldData().setAllowCommands(false);
    }
}
