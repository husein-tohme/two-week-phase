package net.husein.twp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The online home of the library. A world cannot live "inside the mod file" (that file sits on each PC), so the
 * one real copy lives online, and each owner's PC borrows it: before playing it takes the newest version, after
 * playing it puts its changes back.
 * <p>
 * "Online" is one of two things:
 * <ul>
 * <li>web storage the mod talks to itself (WebDAV, e.g. a free Koofr account). The mod then knows exactly when
 *     a save has finished, so it can say so, or close the game.</li>
 * <li>a folder called "TwoWeekPhase Cloud" that a cloud-drive app keeps the same on both PCs. Simple, but only
 *     that app knows when its upload is done.</li>
 * </ul>
 * A version is described by manifest.json, written last. File contents are stored under their checksum and never
 * overwritten, so an interrupted save leaves the previous version complete, and continues where it stopped.
 */
public final class TwpCloud {
    private TwpCloud() { }

    private static final Logger LOG = LoggerFactory.getLogger("twoweekphase");
    public static final String FOLDER = "TwoWeekPhase Cloud";
    private static final String WEB_ROOT = "TwoWeekPhaseCloud";
    /** returned by pull(): the newest version is not completely online (or has not completely reached this PC) yet */
    public static final String ARRIVING = "ARRIVING";

    public static final class Entry { public long size; public long time; public String sha1; }

    /** manifest.json: exactly which files make up the version saved online. */
    public static final class Meta {
        public int revision;
        public String by;
        public String at;
        public Map<String, Entry> files = new TreeMap<>();
    }

    /** playing.json: who has the world open right now. */
    public static final class Playing { public String who; public long since; public long beat; }

    /** index.json: the worlds that are online. */
    public static final class Index { public List<String> worlds = new ArrayList<>(); }

    /** cloud.json, kept on each PC next to its copy: which online version that copy matches. */
    public static final class Local {
        public int base;
        /** true from the moment this PC opens the world until its progress has been put online */
        public boolean unpushed;
        /** true when putting it online was refused because the other owner had already saved a newer version */
        public boolean refused;
        public Map<String, long[]> files = new TreeMap<>();
    }

    /** settings.json in the mod's folder. */
    public static final class Settings {
        public boolean online = true;
        public boolean closeWhenDone;
        public String url, user, password;
    }

    public interface Progress { void at(long doneBytes, long totalBytes); }

    public enum State { NO_CLOUD, UNREACHABLE, NOTHING_ONLINE, SAME, NEWER, ARRIVING }

    public static final class Status {
        public State state = State.NO_CLOUD;
        public int missing, total;
        public String by, at;
    }

    // ------------------------------------------------------------ settings

    private static Settings settings;

    public static synchronized Settings settings() {
        if (settings == null) {
            try {
                settings = TwpFiles.read(TwpFiles.dataDir().resolve("settings.json"), Settings.class);
            } catch (Throwable ignored) {
            }
            if (settings == null) settings = new Settings();
        }
        return settings;
    }

    public static synchronized void saveSettings() {
        TwpFiles.write(TwpFiles.dataDir().resolve("settings.json"), settings());
        storeAt = 0;
    }

    /** One line of text that holds the web storage's address and password, to give to the other owner. */
    public static String code() {
        Settings s = settings();
        if (s.url == null || s.url.isBlank()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("u", s.url);
        o.addProperty("n", s.user == null ? "" : s.user);
        o.addProperty("p", s.password == null ? "" : s.password);
        return "TWP1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a code made by code(). Returns {address, user, password}, or null if it is not one. */
    public static String[] readCode(String code) {
        try {
            code = code.trim();
            if (!code.startsWith("TWP1:")) return null;
            JsonObject o = TwpFiles.GSON.fromJson(new String(Base64.getUrlDecoder().decode(code.substring(5)), StandardCharsets.UTF_8), JsonObject.class);
            return new String[]{o.get("u").getAsString(), o.get("n").getAsString(), o.get("p").getAsString()};
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ where "online" is

    /** A place that holds small named files. Paths use "/" and only letters, digits, "-" and ".". */
    public interface Store {
        String name();
        /** true when "saved" means it really is online; false when a cloud-drive app still has to upload it */
        boolean exact();
        /** null if it is not there */
        byte[] read(String path) throws IOException;
        void write(String path, byte[] data) throws IOException;
        /** -1 if it is not there */
        long size(String path) throws IOException;
        void put(String path, Path file) throws IOException;
        /** throws NoSuchFileException if it is not there */
        void get(String path, Path file) throws IOException;
        void delete(String path) throws IOException;
    }

    private static Store cachedStore;
    private static String cachedKey;
    private static long storeAt;

    /** Where the library is saved online, or null when online saving is off or not set up. */
    public static synchronized Store store() {
        long now = System.currentTimeMillis();
        if (now - storeAt > 15_000) {
            storeAt = now;
            String url = System.getProperty("twp.cloudUrl"), dir = System.getProperty("twp.cloudDir");   // tests
            Settings s = settings();
            String user = url != null ? System.getProperty("twp.cloudUser", "") : s.user, password = url != null ? System.getProperty("twp.cloudPassword", "") : s.password;
            if (url == null && dir == null && s.online && s.url != null && !s.url.isBlank()) url = s.url;
            String key;
            if (url != null) key = "web|" + url + "|" + user + "|" + password;
            else if (dir != null) key = "dir|" + dir;
            else if (!s.online) key = "off";
            else {
                Path folder = find();
                key = folder == null ? "none" : "dir|" + folder;
            }
            if (!key.equals(cachedKey)) {
                cachedKey = key;
                try {
                    cachedStore = key.startsWith("web|") ? new WebStore(url, user, password) : key.startsWith("dir|") ? new FolderStore(Path.of(key.substring(4))) : null;
                } catch (Exception e) {                                 // an address that is not an address
                    cachedStore = null;
                }
            }
        }
        return cachedStore;
    }

    public static synchronized void forget() { storeAt = 0; }

    private static Path find() {
        try {
            Path cfg = TwpFiles.dataDir().resolve("cloud-folder.txt");  // optional: one line with the folder to use
            if (Files.exists(cfg)) {
                for (String line : Files.readAllLines(cfg)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    Path p = Path.of(line);
                    return Files.isDirectory(p) ? p : null;
                }
            }
        } catch (Throwable ignored) {
        }
        for (Path base : drives()) {
            Path p = base.resolve(FOLDER);
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    /** The folders that cloud-drive apps keep in sync on this PC. */
    public static List<Path> drives() {
        List<Path> out = new ArrayList<>();
        for (String var : new String[]{"OneDriveConsumer", "OneDrive"}) add(out, System.getenv(var));
        for (String var : new String[]{"APPDATA", "LOCALAPPDATA"}) {   // Dropbox writes down where its folder is
            try {
                String base = System.getenv(var);
                if (base == null) continue;
                Path info = Path.of(base, "Dropbox", "info.json");
                if (!Files.exists(info)) continue;
                JsonObject o = TwpFiles.GSON.fromJson(Files.readString(info, StandardCharsets.UTF_8), JsonObject.class);
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("path")) add(out, e.getValue().getAsJsonObject().get("path").getAsString());
                }
            } catch (Exception ignored) {
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) for (String name : new String[]{"Dropbox", "Google Drive", "My Drive"}) add(out, home + File.separator + name);
        File[] roots = File.listRoots();                               // Google Drive for desktop shows up as its own drive letter
        if (roots != null) {
            for (File r : roots) {
                if (r.getPath().toUpperCase().startsWith("C")) continue;
                try (Stream<Path> s = Files.list(r.toPath())) {
                    for (Path child : s.limit(40).toList()) if (Files.isDirectory(child)) out.add(child);
                } catch (Exception ignored) {
                }
                // a folder someone else shared, added as a shortcut, really lives here: <drive>\.shortcut-targets-by-id\<id>\<folder>
                Path shared = r.toPath().resolve(".shortcut-targets-by-id");
                if (Files.isDirectory(shared)) {
                    try (Stream<Path> s = Files.list(shared)) {
                        for (Path id : s.limit(200).toList()) if (Files.isDirectory(id)) out.add(id);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return out;
    }

    private static void add(List<Path> out, String dir) {
        try {
            if (dir != null && !dir.isBlank() && Files.isDirectory(Path.of(dir))) out.add(Path.of(dir));
        } catch (Exception ignored) {
        }
    }

    /** A folder on this PC that a cloud-drive app keeps in sync. */
    static final class FolderStore implements Store {
        private final Path root;

        FolderStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

        private Path at(String path) throws IOException {
            Path p = root.resolve(path).normalize();
            if (!p.startsWith(root)) throw new IOException("Outside the shared folder: " + path);
            return p;
        }

        /** the cloud-drive app may hold a file for a moment, so everything is tried a few times */
        private interface Action { void run() throws IOException; }

        private static void retry(Action a) throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
                    a.run();
                    return;
                } catch (NoSuchFileException e) {
                    throw e;
                } catch (IOException e) {
                    last = e;
                    try { Thread.sleep(750); } catch (InterruptedException ignored) { }
                }
            }
            throw last;
        }

        @Override public String name() { return root.toString(); }
        @Override public boolean exact() { return false; }

        @Override public byte[] read(String path) throws IOException {
            Path p = at(path);
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        }

        @Override public void write(String path, byte[] data) throws IOException {
            Path p = at(path);
            retry(() -> {
                Files.createDirectories(p.getParent());
                Files.write(p, data);
            });
        }

        @Override public long size(String path) throws IOException {
            Path p = at(path);
            return Files.exists(p) ? Files.size(p) : -1;
        }

        @Override public void put(String path, Path file) throws IOException {
            Path p = at(path);
            retry(() -> {
                Files.createDirectories(p.getParent());
                Files.copy(file, p, StandardCopyOption.REPLACE_EXISTING);
            });
        }

        @Override public void get(String path, Path file) throws IOException {
            Path p = at(path);
            if (!Files.exists(p)) throw new NoSuchFileException(path);
            retry(() -> Files.copy(p, file, StandardCopyOption.REPLACE_EXISTING));
        }

        @Override public void delete(String path) throws IOException {
            Files.deleteIfExists(at(path));
        }
    }

    /** Web storage that speaks WebDAV (plain web requests with a user name and an app password). */
    static final class WebStore implements Store {
        private final String base, auth, host;
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
        private final Set<String> made = ConcurrentHashMap.newKeySet();

        WebStore(String url, String user, String password) {
            String u = url.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            base = u + "/" + WEB_ROOT;
            host = URI.create(u).getHost();
            auth = "Basic " + Base64.getEncoder().encodeToString(((user == null ? "" : user) + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        }

        private HttpRequest.Builder to(String path) {
            return HttpRequest.newBuilder(URI.create(path.isEmpty() ? base : base + "/" + path)).header("Authorization", auth).timeout(Duration.ofMinutes(30));
        }

        private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    HttpResponse<T> r = http.send(request, handler);
                    if (r.statusCode() == 401 || r.statusCode() == 403) throw new IOException("The online storage refused the user name or password.");
                    if (r.statusCode() < 500 && r.statusCode() != 429) return r;
                    last = new IOException("The online storage answered " + r.statusCode());
                } catch (InterruptedException e) {
                    throw new IOException(e);
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().startsWith("The online storage refused")) throw e;
                    last = e;
                }
                try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) { }
            }
            throw last;
        }

        /** web storage wants every folder to exist before a file is put in it */
        private void folders(String path) throws IOException {
            folder("");
            String cur = "";
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                cur = cur.isEmpty() ? parts[i] : cur + "/" + parts[i];
                folder(cur);
            }
        }

        private void folder(String dir) throws IOException {
            if (!made.add(dir)) return;
            int code = send(to(dir).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code != 201 && code != 405 && code != 200 && code != 204) {   // 405: it is already there
                made.remove(dir);
                throw new IOException("Could not make a folder online (" + code + ")");
            }
        }

        private static void ok(int code, String what) throws IOException {
            if (code != 200 && code != 201 && code != 204) throw new IOException(what + " failed (" + code + ")");
        }

        @Override public String name() { return host; }
        @Override public boolean exact() { return true; }

        @Override public byte[] read(String path) throws IOException {
            HttpResponse<byte[]> r = send(to(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 404) return null;
            ok(r.statusCode(), "Reading");
            return r.body();
        }

        @Override public void write(String path, byte[] data) throws IOException {
            folders(path);
            ok(send(to(path).PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), "Saving");
        }

        @Override public long size(String path) throws IOException {
            HttpResponse<Void> r = send(to(path).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == 404) return -1;
            ok(r.statusCode(), "Checking");
            return r.headers().firstValueAsLong("content-length").orElse(-2);
        }

        @Override public void put(String path, Path file) throws IOException {
            folders(path);
            ok(send(to(path).PUT(HttpRequest.BodyPublishers.ofFile(file)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), "Uploading");
        }

        @Override public void get(String path, Path file) throws IOException {
            Files.createDirectories(file.getParent());
            HttpResponse<Path> r = send(to(path).GET().build(), HttpResponse.BodyHandlers.ofFile(file));
            if (r.statusCode() == 404) {
                Files.deleteIfExists(file);
                throw new NoSuchFileException(path);
            }
            ok(r.statusCode(), "Downloading");
        }

        @Override public void delete(String path) throws IOException {
            int code = send(to(path).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code != 404) ok(code, "Removing");
        }
    }

    // ------------------------------------------------------------ small helpers

    private static <T> T readJson(Store st, String path, Class<T> type) throws IOException {
        byte[] b = st.read(path);
        if (b == null) return null;
        try {
            return TwpFiles.GSON.fromJson(new String(b, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJson(Store st, String path, Object value) throws IOException {
        st.write(path, TwpFiles.GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String id(String lineage) {
        return UUID.fromString(lineage).toString();
    }

    private static String blob(String lineage, String sha1) throws IOException {
        if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) throw new IOException("Not a checksum: " + sha1);
        return id(lineage) + "/blobs/" + sha1;
    }

    private static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static Path inside(Path root, String relative) throws IOException {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw new IOException("A file name tries to leave its folder: " + relative);
        return p;
    }

    private static Meta meta(Store st, String lineage) throws IOException {
        Meta m = readJson(st, id(lineage) + "/manifest.json", Meta.class);
        if (m != null && m.files == null) m.files = new TreeMap<>();
        return m;
    }

    public static Local local(TwpFiles.Slot slot) {
        Local l = TwpFiles.read(slot.dir.resolve("cloud.json"), Local.class);
        if (l == null) l = new Local();
        if (l.files == null) l.files = new TreeMap<>();
        return l;
    }

    private static void save(TwpFiles.Slot slot, Local l) {
        TwpFiles.write(slot.dir.resolve("cloud.json"), l);
    }

    /** Called when this PC opens the world: from now on its copy holds progress that is not online yet. */
    public static void markOpened(TwpFiles.Slot slot) {
        if (store() == null) return;
        Local l = local(slot);
        if (!l.unpushed) {
            l.unpushed = true;
            save(slot, l);
        }
    }

    /** True if this PC's copy holds a finished session that still has to go online. */
    public static boolean waiting(TwpFiles.Slot slot) {
        Local l = local(slot);
        return l.unpushed && !l.refused && slot.ready() && !Files.exists(slot.yieldFlag());
    }

    /** The version that is online right now, or null if there is none or it cannot be reached. */
    public static Meta online(String lineage) {
        try {
            Store st = store();
            return st == null ? null : meta(st, lineage);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ after playing: put the world online

    /**
     * Puts this PC's copy online, changed files only. Returns one line for the player, or null when online saving
     * is off. The line starts with "Saved online" on success. The world must be closed.
     */
    public static String push(TwpFiles.Slot slot, TwpFiles.Owners owners, String me, Progress progress) {
        Store st = store();
        if (st == null) return null;
        try {
            String id = id(owners.lineage);
            Meta cloud = meta(st, owners.lineage);
            Local local = local(slot);
            if (cloud != null && cloud.revision > local.base) {
                LOG.warn("Not saved online: revision {} is already there, this copy is based on {}", cloud.revision, local.base);
                local.refused = true;
                save(slot, local);
                return "NOT saved online: " + cloud.by + " saved a newer version while you played. Your session is safe on this PC only.";
            }
            Path world = slot.worldDir().toAbsolutePath().normalize();
            Map<String, long[]> mine = TwpFiles.manifest(world);
            mine.keySet().removeIf(k -> k.endsWith(".twp-real"));

            // 1. what does every file look like now, and which contents are not online yet
            Set<String> have = new HashSet<>();
            if (cloud != null) for (Entry e : cloud.files.values()) have.add(e.sha1);
            Meta next = new Meta();
            next.revision = (cloud == null ? 0 : cloud.revision) + 1;
            next.by = me;
            next.at = Instant.now().toString();
            Map<String, Path> send = new LinkedHashMap<>();
            long total = 0;
            for (Map.Entry<String, long[]> e : mine.entrySet()) {
                long[] now = e.getValue();
                Entry old = cloud == null ? null : cloud.files.get(e.getKey());
                long[] was = local.files.get(e.getKey());
                if (old != null && was != null && was.length == 2 && was[0] == now[0] && was[1] == now[1] && old.size == now[0]) {
                    next.files.put(e.getKey(), old);                    // untouched since the version online
                    continue;
                }
                Entry n = new Entry();
                n.size = now[0];
                n.time = now[1];
                n.sha1 = sha1(world.resolve(e.getKey()));
                next.files.put(e.getKey(), n);
                if (!have.contains(n.sha1) && send.putIfAbsent(n.sha1, world.resolve(e.getKey())) == null) total += n.size;
            }
            // 2. send them. Contents are stored under their checksum, so nothing online is overwritten.
            long done = 0, sent = 0;
            progress.at(0, total);
            for (Map.Entry<String, Path> e : send.entrySet()) {
                long size = Files.size(e.getValue());
                String at = blob(owners.lineage, e.getKey());
                if (st.size(at) != size) {                              // (already there if an earlier save was interrupted)
                    st.put(at, e.getValue());
                    sent += size;
                }
                done += size;
                progress.at(done, total);
            }
            // 3. the first time: say who owns it, and list it
            if (cloud == null) {
                writeJson(st, id + "/owners.json", owners);
                Index index = readJson(st, "index.json", Index.class);
                if (index == null || index.worlds == null) index = new Index();
                if (!index.worlds.contains(id)) {
                    index.worlds.add(id);
                    writeJson(st, "index.json", index);
                }
            }
            // 4. the switch: from this moment the new version is the one online
            writeJson(st, id + "/manifest.json", next);
            // 5. contents no version needs any more
            if (cloud != null) {
                Set<String> keep = new HashSet<>();
                for (Entry e : next.files.values()) keep.add(e.sha1);
                for (String old : have) {
                    if (keep.contains(old)) continue;
                    try { st.delete(blob(owners.lineage, old)); } catch (IOException ignored) { }
                }
            }
            try { st.delete(id + "/playing.json"); } catch (IOException ignored) { }
            local.base = next.revision;
            local.unpushed = false;
            local.refused = false;
            local.files = mine;
            save(slot, local);
            long mb = Math.max(1, sent >> 20);
            LOG.info("Saved online: revision {}, {} MB sent", next.revision, mb);
            return st.exact() ? "Saved online: " + mb + " MB sent. It is finished: you can turn off your PC."
                    : "Saved online: " + mb + " MB changed. Keep this PC on until your cloud-drive app has finished uploading.";
        } catch (Exception e) {
            LOG.error("Could not put the world online", e);
            return "Could NOT save online: " + (e.getMessage() != null && e.getMessage().startsWith("The online storage") ? e.getMessage() : e.getClass().getSimpleName())
                    + " Your progress is safe on this PC and goes online next time.";
        }
    }

    // ------------------------------------------------------------ before playing: take the newest version

    /** How this PC's copy compares with what is online. */
    public static Status status(TwpFiles.Slot slot, TwpFiles.Owners owners) {
        Status s = new Status();
        Store st = store();
        if (st == null) return s;
        try {
            Meta cloud = meta(st, owners.lineage);
            if (cloud == null) {
                s.state = State.NOTHING_ONLINE;
                return s;
            }
            s.by = cloud.by;
            s.at = cloud.at;
            s.total = cloud.files.size();
            if (cloud.revision <= local(slot).base && slot.ready()) {
                s.state = State.SAME;
                return s;
            }
            if (!st.exact()) {                                          // a synced folder: has everything reached this PC?
                Map<String, long[]> mine = TwpFiles.manifest(slot.worldDir());
                for (Map.Entry<String, Entry> e : cloud.files.entrySet()) {
                    long[] h = mine.get(e.getKey());
                    if (h != null && h[0] == e.getValue().size && h[1] == e.getValue().time) continue;
                    if (st.size(blob(owners.lineage, e.getValue().sha1)) != e.getValue().size) s.missing++;
                }
            }
            s.state = s.missing == 0 ? State.NEWER : State.ARRIVING;
        } catch (Exception e) {
            LOG.warn("Could not reach the online storage: {}", e.toString());
            s.state = State.UNREACHABLE;
            s.by = e.getMessage();
        }
        return s;
    }

    /**
     * Brings this PC's copy up to the version saved online. Every file is checked against its checksum before
     * the world is touched. Returns null when done, ARRIVING when the version is not completely there yet,
     * or what went wrong. The world must be closed.
     */
    public static String pull(TwpFiles.Slot slot, TwpFiles.Owners owners, Progress progress) {
        Path staging = slot.dir.resolve("pulling");
        try {
            Store st = store();
            Meta cloud = st == null ? null : meta(st, owners.lineage);
            if (cloud == null) return "There is nothing online for this world.";
            Local local = local(slot);
            Path world = slot.worldDir().toAbsolutePath().normalize();
            boolean had = slot.hasWorld();
            Map<String, long[]> mine = TwpFiles.manifest(world);

            List<String> need = new ArrayList<>();
            long total = 0;
            for (Map.Entry<String, Entry> e : cloud.files.entrySet()) {
                long[] have = mine.get(e.getKey());
                if (have == null || have[0] != e.getValue().size || have[1] != e.getValue().time) {
                    need.add(e.getKey());
                    total += e.getValue().size;
                }
            }
            TwpFiles.deleteTree(staging);
            long done = 0;
            progress.at(0, total);
            for (String path : need) {                                   // 1. fetch and check everything first
                Entry e = cloud.files.get(path);
                Path to = inside(staging.toAbsolutePath().normalize(), path);
                Files.createDirectories(to.getParent());
                try {
                    st.get(blob(owners.lineage, e.sha1), to);
                } catch (NoSuchFileException missing) {
                    TwpFiles.deleteTree(staging);
                    return ARRIVING;
                }
                if (Files.size(to) != e.size || !sha1(to).equals(e.sha1)) {
                    TwpFiles.deleteTree(staging);
                    return ARRIVING;
                }
                done += e.size;
                progress.at(done, total);
            }
            // 2. all of it is here and correct: now change the world
            Files.createDirectories(world);
            Files.writeString(slot.syncingFlag(), "receiving");
            boolean keep = had && local.unpushed;                        // this PC held progress that never went online: keep it aside
            Path keepDir = slot.replacedDir().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
            for (String path : need) {
                Path target = inside(world, path);
                Files.createDirectories(target.getParent());
                if (keep) TwpFiles.setAside(world, target, keepDir);
                Files.move(inside(staging.toAbsolutePath().normalize(), path), target, StandardCopyOption.REPLACE_EXISTING);
                TwpFiles.setTime(target, cloud.files.get(path).time);
            }
            for (String path : mine.keySet()) {
                if (cloud.files.containsKey(path)) continue;
                if (keep) TwpFiles.setAside(world, world.resolve(path), keepDir);
                else Files.deleteIfExists(world.resolve(path));
            }
            Files.deleteIfExists(slot.syncingFlag());
            TwpFiles.deleteTree(staging);
            if (keep) TwpFiles.pruneReplaced(slot);
            local.base = cloud.revision;
            local.unpushed = false;
            local.refused = false;
            local.files = TwpFiles.manifest(world);
            save(slot, local);
            LOG.info("Took revision {} from online: {} file(s) changed{}", cloud.revision, need.size(), keep ? " (this PC's own unsaved version was kept aside)" : "");
            return null;
        } catch (Exception e) {
            LOG.error("Could not take the online version", e);
            try { TwpFiles.deleteTree(staging); } catch (IOException ignored) { }
            return "Could not get the online version (" + e.getClass().getSimpleName() + "). Your copy was not changed.";
        }
    }

    // ------------------------------------------------------------ who is playing right now

    /** True if the other owner had the world open within the last three minutes. */
    public static boolean heldByOther(String lineage, String me) {
        try {
            Store st = store();
            Playing p = st == null ? null : readJson(st, id(lineage) + "/playing.json", Playing.class);
            return p != null && p.who != null && !p.who.equalsIgnoreCase(me) && System.currentTimeMillis() - p.beat < 180_000;
        } catch (Exception e) {
            return false;
        }
    }

    /** Says "I have this world open". Repeated every minute while playing. */
    public static void beat(String lineage, String me) {
        try {
            Store st = store();
            if (st == null || meta(st, lineage) == null) return;
            String path = id(lineage) + "/playing.json";
            Playing old = readJson(st, path, Playing.class);
            Playing p = new Playing();
            p.who = me;
            p.beat = System.currentTimeMillis();
            p.since = old != null && me.equalsIgnoreCase(old.who) && p.beat - old.beat < 180_000 ? old.since : p.beat;
            writeJson(st, path, p);
        } catch (Exception e) {
            LOG.debug("Could not mark the world as open", e);
        }
    }

    // ------------------------------------------------------------ worlds that are online but not on this PC yet

    /** Worlds online that list this player as an owner, but are not in this PC's library yet, are added to it. Returns how many. */
    public static int adopt(String me) {
        int added = 0;
        try {
            Store st = store();
            Index index = st == null ? null : readJson(st, "index.json", Index.class);
            if (index == null || index.worlds == null) return 0;
            for (String lineage : index.worlds) {
                String id;
                try {
                    id = id(lineage);
                } catch (Exception e) {
                    continue;
                }
                if (TwpFiles.byLineage(id) != null) continue;
                TwpFiles.Owners o = readJson(st, id + "/owners.json", TwpFiles.Owners.class);
                if (o == null || o.players == null || o.players.size() != 2 || !id.equals(o.lineage)) continue;
                if (o.players.stream().noneMatch(p -> p.name != null && p.name.equalsIgnoreCase(me))) continue;
                TwpFiles.Slot slot = TwpFiles.newSlot(id);
                TwpFiles.write(slot.sealedFile(), o);
                LOG.info("\"{}\" was found online and added to the library", slot.title());
                added++;
            }
        } catch (Throwable e) {
            LOG.warn("Could not look for worlds online: {}", e.toString());
        }
        return added;
    }

    /** Checks that the storage can be written to and read from. Returns null if it works, or what is wrong. */
    public static String test(Store st) {
        try {
            byte[] hello = ("hello " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
            st.write("hello.txt", hello);
            byte[] back = st.read("hello.txt");
            return back != null && new String(back, StandardCharsets.UTF_8).equals(new String(hello, StandardCharsets.UTF_8)) ? null : "It answered, but did not keep the test file.";
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    /** A web storage with these details, for testing them before they are saved. */
    public static Store web(String url, String user, String password) {
        return new WebStore(url, user, password);
    }
}
