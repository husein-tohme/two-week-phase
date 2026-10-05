package net.husein.twp.client;

import net.husein.twp.TwoWeekPhaseMod;
import net.husein.twp.TwpFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Adds one of the player's single-player worlds to the mod: a COPY of it becomes the Two Week Phase world.
 * The original stays in the single-player list, untouched.
 */
public final class TwpImport {
    private TwpImport() { }

    public record Entry(Path dir, String folder, String name, long modified) {
        public String label() { return name.equals(folder) ? name : name + "  (" + folder + ")"; }
    }

    public interface Progress { void at(long doneBytes, long totalBytes); }

    /** The worlds in this game's single-player list, most recently played first. */
    public static List<Entry> list(Minecraft client) {
        List<Entry> out = new ArrayList<>();
        Path saves = client.getLevelSource().getBaseDir();
        if (!Files.isDirectory(saves)) return out;
        try (Stream<Path> s = Files.list(saves)) {
            for (Path dir : (Iterable<Path>) s::iterator) {
                Path dat = dir.resolve("level.dat");
                if (!Files.isRegularFile(dat)) continue;
                String folder = dir.getFileName().toString();
                String name = folder;
                try {
                    name = NbtIo.readCompressed(dat, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data").getStringOr("LevelName", folder);
                    if (name.isBlank()) name = folder;
                } catch (Exception ignored) {
                    // unreadable name: the folder name will do
                }
                out.add(new Entry(dir, folder, name, Files.getLastModifiedTime(dat).toMillis()));
            }
        } catch (IOException e) {
            TwoWeekPhaseMod.LOG.warn("Could not list {}", saves, e);
        }
        out.sort(Comparator.comparingLong(Entry::modified).reversed());
        return out;
    }

    /** Copies the world into the mod and fixes its two owners. Returns what went wrong, or null if it worked. */
    public static String run(Path source, TwpFiles.Owners owners, Progress progress) {
        TwpFiles.Slot slot = TwpFiles.newSlot(owners.lineage);   // a brand new folder of the library, just for this world
        Path tmp = slot.worldsDir().resolve(TwpFiles.LEVEL + ".adding");
        try {
            if (!Files.isRegularFile(source.resolve("level.dat"))) return "That world has no level.dat file.";
            if (inUse(source)) return "That world is open in a Minecraft window right now. Close it first.";
            Files.createDirectories(slot.worldsDir());
            copyTree(source, tmp, progress);
            makeItTwoWeekPhase(tmp);
            Files.move(tmp, slot.worldDir());
            TwpFiles.write(slot.sealedFile(), owners);       // from this moment the two owners are fixed for good
            TwoWeekPhaseMod.LOG.info("Added the single-player world '{}' to the library, shared by {}", source.getFileName(), owners.names());
            return null;
        } catch (Exception e) {
            TwoWeekPhaseMod.LOG.error("Could not add the world {}", source, e);
            try { deleteTree(slot.dir); } catch (IOException ignored) { }   // only the new, unfinished library folder
            return "Could not add that world (" + e.getClass().getSimpleName() + "). Your original world was not changed.";
        }
    }

    /** True while some game has the world open. */
    private static boolean inUse(Path world) {
        Path lock = world.resolve("session.lock");
        if (!Files.exists(lock)) return false;
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock l = ch.tryLock()) {
            return l == null;
        } catch (Exception e) {
            return true;
        }
    }

    private static void copyTree(Path from, Path to, Progress progress) throws IOException {
        long total = 0;
        List<Path> all;
        try (Stream<Path> s = Files.walk(from)) {
            all = s.toList();
        }
        for (Path p : all) if (Files.isRegularFile(p)) total += Files.size(p);
        long done = 0;
        for (Path p : all) {
            Path target = to.resolve(from.relativize(p).toString());
            if (Files.isDirectory(p)) { Files.createDirectories(target); continue; }
            if (!Files.isRegularFile(p) || p.getFileName().toString().equals("session.lock")) continue;
            Files.createDirectories(target.getParent());
            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            done += Files.size(target);
            progress.at(done, total);
        }
    }

    /** The same fixed settings a world created by the mod has: hardcore, hard, no cheats. It keeps its name. Only the copy is edited. */
    private static void makeItTwoWeekPhase(Path world) throws IOException {
        Path dat = world.resolve("level.dat");
        CompoundTag root = NbtIo.readCompressed(dat, NbtAccounter.unlimitedHeap());
        CompoundTag data = root.getCompound("Data").orElseThrow(() -> new IOException("level.dat has no Data"));
        data.putBoolean("allowCommands", false);
        data.putInt("GameType", 0);
        Optional<CompoundTag> difficulty = data.getCompound("difficulty_settings");
        if (difficulty.isPresent()) {
            difficulty.get().putString("difficulty", "hard");
            difficulty.get().putBoolean("hardcore", true);
            difficulty.get().putBoolean("locked", true);
        } else {                                             // a world last saved by an older Minecraft
            data.putBoolean("hardcore", true);
            data.putByte("Difficulty", (byte) 3);
            data.putBoolean("DifficultyLocked", true);
        }
        NbtIo.writeCompressed(root, dat);
        Files.deleteIfExists(world.resolve("level.dat_old"));
        Files.deleteIfExists(world.resolve(TwpFiles.OWNERS_IN_WORLD));
        Files.deleteIfExists(world.resolve(TwpFiles.STATE_IN_WORLD));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
