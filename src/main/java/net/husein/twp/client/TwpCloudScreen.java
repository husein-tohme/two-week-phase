package net.husein.twp.client;

import net.husein.twp.TwoWeekPhaseMod;
import net.husein.twp.TwpCloud;
import net.husein.twp.TwpFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * What happens between pressing Play and being in the world, when the library is saved online:
 * if the other owner is in the world right now, join them; otherwise take the newest version
 * from online, then open it.
 */
public class TwpCloudScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GOLD = 0xFFFFD050;

    /** WORKING: nothing to press. CHOICE: "check again", maybe "play my last version", "back". PROBLEM: "back". */
    private enum Mode { WORKING, CHOICE, PROBLEM }

    private final Screen parent;
    private final TwpFiles.Slot slot;
    private final TwpFiles.Owners owners;
    private final String me, friendName;
    private volatile Mode mode = Mode.WORKING;
    private volatile String line1 = "Checking for the newest version...", line2 = "";
    private volatile String anyway;          // label of the "go on regardless" button, or null for none
    private boolean started;
    private boolean ignoreBusy, ignoreOnline;

    public TwpCloudScreen(Screen parent, TwpFiles.Slot slot) {
        super(Component.literal(slot.title()));
        this.parent = parent;
        this.slot = slot;
        this.owners = slot.sealed();
        this.me = Minecraft.getInstance().getUser().getName();
        TwpFiles.Owner first = owners == null ? null : owners.players.get(0);
        this.friendName = first == null ? "" : first.name.equalsIgnoreCase(me) ? owners.players.get(1).name : first.name;
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 + 30;
        if (mode == Mode.CHOICE) {
            addRenderableWidget(Button.builder(Component.literal("Check again"), b -> restart()).bounds(cx - 100, y, 200, 20).build());
            String label = anyway;
            if (label != null) {
                addRenderableWidget(Button.builder(Component.literal(label), b -> {
                    if (label.equals("Play anyway")) ignoreBusy = true;   // still takes the newest version first
                    else ignoreOnline = true;                             // plays this PC's copy as it is
                    restart();
                }).bounds(cx - 100, y + 24, 200, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx - 100, y + 48, 200, 20).build());
        } else if (mode == Mode.PROBLEM) {
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
        }
        if (!started) {
            started = true;
            run();
        }
    }

    private void restart() {
        show(Mode.WORKING, "Checking for the newest version...", "", null);
        run();
    }

    private void show(Mode m, String a, String b, String goOn) {
        mode = m;
        line1 = a;
        line2 = b;
        anyway = goOn;
        minecraft.execute(this::rebuildWidgets);
    }

    private void run() {
        Minecraft client = minecraft;
        Thread t = new Thread(() -> {
            try {
                work(client);
            } catch (Throwable e) {
                TwoWeekPhaseMod.LOG.error("Could not prepare the world", e);
                show(Mode.PROBLEM, "Something went wrong (" + e.getClass().getSimpleName() + ").", "Your copy on this PC was not changed.", null);
            }
        }, "Two Week Phase: opening");
        t.setDaemon(true);
        t.start();
    }

    private static String mb(long done, long total) {
        return (done >> 20) + " of " + Math.max(1, total >> 20) + " MB";
    }

    private void work(Minecraft client) {
        if (owners == null) {
            show(Mode.PROBLEM, "This world has no owners record on this PC.", "", null);
            return;
        }
        String mine = slot.ready() ? "Play my last version instead" : null;
        if (TwpSave.busy(slot)) {
            show(Mode.CHOICE, "Your last session in this world is still being put online.", "Wait until that is finished, then check again.", null);
            return;
        }
        // 1. is the other owner in the world right now? Then there is nothing to fetch: join them.
        EssentialBridge.Live live = ignoreBusy ? null : EssentialBridge.liveFor(client, slot);
        if (live != null) {
            line1 = friendName + " is in the world right now. Joining...";
            client.execute(() -> EssentialBridge.join(client, parent, live.host(), slot.title()));
            return;
        }
        if (!ignoreOnline) {
            if (!ignoreBusy && TwpCloud.heldByOther(owners.lineage, me)) {
                show(Mode.CHOICE, friendName + " has this world open right now.",
                        "Ask " + friendName + " to invite you. If you both play separately, only one version can be kept.", "Play anyway");
                return;
            }
            // 2. a session of ours that never went online goes first
            if (TwpCloud.waiting(slot)) {
                line1 = "Putting your last session online first...";
                String note = TwpCloud.push(slot, owners, me, (done, total) -> line2 = mb(done, total));
                TwoWeekPhaseMod.LOG.info("Late save online: {}", note);
            }
            // 3. take the newest version from online
            line1 = "Checking for the newest version...";
            line2 = "";
            TwpCloud.Status s = TwpCloud.status(slot, owners);
            if (s.state == TwpCloud.State.UNREACHABLE) {
                show(Mode.CHOICE, "The online storage cannot be reached. Is the internet working?",
                        "If you play now and " + friendName + " saves in the meantime, only one version can be kept.", mine);
                return;
            }
            if (s.state == TwpCloud.State.ARRIVING) {
                arriving(s, mine);
                return;
            }
            if (s.state == TwpCloud.State.NEWER) {
                line1 = "Getting the newest version (saved by " + s.by + ")...";
                String problem = TwpCloud.pull(slot, owners, (done, total) -> line2 = mb(done, total));
                if (TwpCloud.ARRIVING.equals(problem)) {
                    arriving(s, mine);
                    return;
                }
                if (problem != null) {
                    show(Mode.PROBLEM, problem, "", null);
                    return;
                }
            } else if (s.state == TwpCloud.State.NOTHING_ONLINE && slot.ready()) {
                line1 = "Putting this world online for the first time...";
                String note = TwpCloud.push(slot, owners, me, (done, total) -> line2 = mb(done, total));
                TwoWeekPhaseMod.LOG.info("First save online: {}", note);
            }
        }
        if (!slot.ready()) {
            show(Mode.PROBLEM, "This PC does not have this world yet.",
                    "It is not online either. " + friendName + " has to open it once, with online saving on.", null);
            return;
        }
        // 4. open it
        TwpCloud.beat(owners.lineage, me);
        line1 = "Opening...";
        line2 = "";
        client.execute(() -> TwpScreen.open(client, slot, parent));
    }

    private void arriving(TwpCloud.Status s, String mine) {
        show(Mode.CHOICE, s.by + "'s newest progress is not completely online yet.",
                "It is still being sent from " + s.by + "'s PC. That PC must be on until it has finished.", mine);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, y = height / 2 - 30;
        graphics.centeredText(font, title, cx, y - 18, GOLD);
        graphics.centeredText(font, Component.literal(TwoWeekPhaseClient.clip(minecraft, line1, width - 20)), cx, y, WHITE);
        graphics.centeredText(font, Component.literal(TwoWeekPhaseClient.clip(minecraft, line2, width - 20)), cx, y + 14, GREY);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return mode != Mode.WORKING;
    }

    @Override
    public void onClose() {
        if (mode != Mode.WORKING) CompatClient.setScreen(minecraft, parent);
    }
}
