package net.husein.twp.client;

import net.husein.twp.TwoWeekPhaseMod;
import net.husein.twp.TwpCloud;
import net.husein.twp.TwpFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Putting a finished session online, when the mod does the uploading itself (web storage).
 * It runs in the background, so the game stays usable, and it knows exactly when it is finished.
 */
final class TwpSave {
    /** the save that is running, or the last one that ran */
    static volatile TwpSave current;
    private static volatile long failedAt;

    final TwpFiles.Slot slot;
    final String title;
    volatile long done, total;
    /** null while it is running */
    volatile String result;
    volatile boolean ok;
    volatile long finishedAt;

    private TwpSave(TwpFiles.Slot slot) {
        this.slot = slot;
        this.title = slot.title();
    }

    boolean running() { return result == null; }

    static boolean busy(TwpFiles.Slot slot) {
        TwpSave s = current;
        return s != null && s.running() && s.slot.dir.equals(slot.dir);
    }

    /** A world on this PC whose last session still has to go online by the mod itself. Null if there is none (or it just failed). */
    static TwpFiles.Slot waiting() {
        TwpCloud.Store st = TwpCloud.store();
        if (st == null || !st.exact() || System.currentTimeMillis() - failedAt < 5 * 60_000) return null;
        for (TwpFiles.Slot s : TwpFiles.slots()) if (TwpCloud.waiting(s)) return s;
        return null;
    }

    static synchronized TwpSave start(Minecraft client, TwpFiles.Slot slot) {
        TwpSave now = current;
        if (now != null && now.running()) return now;
        TwpSave job = new TwpSave(slot);
        current = job;
        String me = client.getUser().getName();
        Thread t = new Thread(() -> {
            String r;
            try {
                TwpFiles.Owners owners = slot.sealed();
                r = owners == null ? null : TwpCloud.push(slot, owners, me, (d, total) -> { job.done = d; job.total = total; });
            } catch (Throwable e) {
                TwoWeekPhaseMod.LOG.error("Saving online failed", e);
                r = "Could NOT save online (" + e.getClass().getSimpleName() + ").";
            }
            if (r == null) r = "Online saving is off, so this session stays on this PC.";
            job.ok = r.startsWith("Saved online");
            if (!job.ok) failedAt = System.currentTimeMillis();
            job.finishedAt = System.currentTimeMillis();
            TwoWeekPhaseMod.cloudNote = r;
            TwoWeekPhaseMod.cloudNoteAt = job.finishedAt;
            job.result = r;
            client.execute(() -> tell(client, job.ok));
        }, "Two Week Phase: saving online");
        t.setDaemon(true);             // if the game is closed half-way, what is online stays the previous complete version; it continues next time
        t.start();
        return job;
    }

    /** A sound, and the game's taskbar button flashes: the save is finished, whatever the player is looking at. */
    private static void tell(Minecraft client, boolean ok) {
        try {
            client.getSoundManager().play(SimpleSoundInstance.forUI(ok ? SoundEvents.PLAYER_LEVELUP : SoundEvents.EXPERIENCE_ORB_PICKUP, ok ? 1.0F : 0.5F));
        } catch (Throwable ignored) {
        }
        try {
            Object window = client.getWindow();              // looked up by name: not every game version has the same window code
            Object handle = window.getClass().getMethod("handle").invoke(window);
            Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwRequestWindowAttention", long.class).invoke(null, handle);
        } catch (Throwable ignored) {
        }
    }
}
