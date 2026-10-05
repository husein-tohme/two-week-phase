package net.husein.twp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Where everything lives, and the small JSON records that go with the world. */
public final class TwpFiles {
    private TwpFiles() { }

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String LEVEL = "Two Week Phase";
    public static final String OWNERS_IN_WORLD = "two-week-phase-owners.json";
    public static final String STATE_IN_WORLD = "two-week-phase-state.json";

    public static final class Owner {
        public String name;
        public String uuid;   // filled in the first time that player is seen
    }

    /** Who owns the world. Fixed for good when the world is created. */
    public static final class Owners {
        public List<Owner> players = new ArrayList<>();
        public String createdBy;
        public String created;
        public String lineage;   // random id of this one world, so copies of it recognise each other
        public String title;     // the name shown in the library (missing for the very first world: it is "Two Week Phase")

        public boolean sameAs(Owners o) {
            if (o == null || lineage == null || !lineage.equals(o.lineage) || players.size() != o.players.size()) return false;
            for (int i = 0; i < players.size(); i++) {
                if (!players.get(i).name.equalsIgnoreCase(o.players.get(i).name)) return false;
            }
            return true;
        }

        public String names() {
            return players.size() == 2 ? players.get(0).name + " and " + players.get(1).name : "?";
        }
    }

    /** What has happened in this copy of the world. Travels with the world. */
    public static final class State {
        /** how many times each owner has hosted this copy: [owner 0, owner 1]. Tells which of two copies is newer. */
        public int[] sessions = {0, 0};
        /** owners (by index) who have died. They stay ghosts. */
        public List<Integer> dead = new ArrayList<>();
    }

    // ------------------------------------------------------------ places

    /**
     * The mod's own folder: a "TwoWeekPhase" folder next to the mod's jar, inside the mods folder.
     * The world is kept here and nowhere else, so it never shows up in Minecraft's list of worlds.
     */
    public static Path dataDir() {
        Path base = FabricLoader.getInstance().getGameDir().resolve("mods");
        try {
            Path origin = FabricLoader.getInstance().getModContainer("twoweekphase").orElseThrow().getOrigin().getPaths().get(0);
            if (Files.isRegularFile(origin) && origin.getFileName().toString().endsWith(".jar")) base = origin.toAbsolutePath().getParent();
        } catch (Exception ignored) {
            // running from an IDE: fall back to <game folder>/mods
        }
        return base.resolve("TwoWeekPhase");
    }

    /** One world of the library, and the folder that holds everything about it. */
    public static final class Slot {
        public final Path dir;

        Slot(Path dir) { this.dir = dir; }

        public Path worldsDir() { return dir.resolve("world"); }
        public Path worldDir() { return worldsDir().resolve(LEVEL); }
        public Path sealedFile() { return dir.resolve("owners.json"); }
        /** Exists while a copy of the world is being received. If it is still there later, the copy is incomplete. */
        public Path syncingFlag() { return dir.resolve("receiving.flag"); }

        /**
         * Exists while this PC has agreed to take the other owner's newer progress in place of its own.
         * It is removed when that has happened, or when this PC opens the world itself again.
         */
        public Path yieldFlag() { return dir.resolve("yield.flag"); }
        /** Files of this PC's copy that were replaced by the other owner's newer progress are kept here, never deleted. */
        public Path replacedDir() { return dir.resolve("replaced"); }

        public boolean hasWorld() { return Files.exists(worldDir().resolve("level.dat")); }
        /** true when this PC holds a complete copy that can be played */
        public boolean ready() { return hasWorld() && !Files.exists(syncingFlag()); }

        public Owners sealed() {
            Owners o = read(sealedFile(), Owners.class);
            return o != null && o.players != null && o.players.size() == 2 && o.lineage != null ? o : null;
        }

        public String title() {
            Owners o = sealed();
            return o != null && o.title != null && !o.title.isBlank() ? o.title : LEVEL;
        }

        /** when this copy was last played, for sorting */
        public long lastPlayed() {
            try {
                return Files.getLastModifiedTime(worldDir().resolve("level.dat")).toMillis();
            } catch (IOException e) {
                return 0;
            }
        }
    }

    /** Where the very first world lives (the only place versions 1.0 and 1.1 knew). It stays there. */
    public static Slot first() { return new Slot(dataDir()); }

    /** Every other world of the library has its own folder in here, named after the world's id. */
    public static Path libraryDir() { return dataDir().resolve("worlds"); }

    /** Every world in the library, most recently played first. */
    public static List<Slot> slots() {
        List<Slot> out = new ArrayList<>();
        Slot f = first();
        if (f.sealed() != null || f.hasWorld()) out.add(f);
        if (Files.isDirectory(libraryDir())) {
            try (Stream<Path> s = Files.list(libraryDir())) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Slot slot = new Slot(p);
                    if (Files.isDirectory(p) && slot.sealed() != null) out.add(slot);
                }
            } catch (IOException e) {
                TwoWeekPhaseMod.LOG.warn("Could not list {}", libraryDir(), e);
            }
        }
        out.sort(java.util.Comparator.comparingLong(Slot::lastPlayed).reversed());
        return out;
    }

    /** The folder for a new world. The id must be a real random id: it comes over the network when a copy is received. */
    public static Slot newSlot(String lineage) {
        return new Slot(libraryDir().resolve(java.util.UUID.fromString(lineage).toString()));
    }

    /** The library world with this id, or null. */
    public static Slot byLineage(String lineage) {
        if (lineage == null) return null;
        for (Slot s : slots()) {
            Owners o = s.sealed();
            if (o != null && lineage.equals(o.lineage)) return s;
        }
        return null;
    }

    /** The library world stored in this folder, or null if the folder is some other world. */
    public static Slot slotOf(Path worldRoot) {
        List<Slot> all = slots();
        Slot f = first();
        if (all.stream().noneMatch(s -> s.dir.equals(f.dir))) all.add(f);
        for (Slot s : all) {
            try {
                if (Files.exists(s.worldDir()) && Files.isSameFile(worldRoot, s.worldDir())) return s;
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------ json

    public static <T> T read(Path file, Class<T> type) {
        try {
            return Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type) : null;
        } catch (Exception e) {
            TwoWeekPhaseMod.LOG.warn("Could not read {}", file, e);
            return null;
        }
    }

    public static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            TwoWeekPhaseMod.LOG.error("Could not write {}", file, e);
        }
    }

    public static State state(Path world) {
        State s = read(world.resolve(STATE_IN_WORLD), State.class);
        if (s == null) s = new State();
        if (s.sessions == null || s.sessions.length != 2) s.sessions = new int[]{0, 0};
        if (s.dead == null) s.dead = new ArrayList<>();
        return s;
    }

    // ------------------------------------------------------------ comparing copies

    /** path -> [size, last-modified ms] for every file of a world (the lock file is left out). */
    public static Map<String, long[]> manifest(Path world) {
        Map<String, long[]> m = new TreeMap<>();
        if (!Files.isDirectory(world)) return m;
        try (Stream<Path> s = Files.walk(world)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p) || p.getFileName().toString().equals("session.lock")) continue;
                m.put(world.relativize(p).toString().replace('\\', '/'), new long[]{Files.size(p), Files.getLastModifiedTime(p).toMillis()});
            }
        } catch (IOException e) {
            TwoWeekPhaseMod.LOG.warn("Could not list {}", world, e);
        }
        return m;
    }

    public static void setTime(Path file, long millis) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
        } catch (IOException ignored) {
        }
    }

    /** Moves a file of this PC's copy into a "replaced" folder instead of losing it. */
    public static void setAside(Path world, Path file, Path keepDir) throws IOException {
        if (!Files.exists(file)) return;
        Path to = keepDir.resolve(world.relativize(file).toString());
        Files.createDirectories(to.getParent());
        Files.move(file, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Only the three most recent sets of replaced files are kept. */
    public static void pruneReplaced(Slot slot) {
        try (Stream<Path> s = Files.list(slot.replacedDir())) {
            List<Path> sets = s.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path old : sets.subList(Math.min(3, sets.size()), sets.size())) deleteTree(old);
        } catch (IOException ignored) {
        }
    }

    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** a >= b in every slot */
    public static boolean atLeast(int[] a, int[] b) {
        return a[0] >= b[0] && a[1] >= b[1];
    }
}
