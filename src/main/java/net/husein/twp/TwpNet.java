package net.husein.twp;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * The messages the two copies of the mod send each other while the owners play together.
 * They carry a copy of the world from the hosting owner to the other owner, so both always hold it.
 */
public final class TwpNet {
    private TwpNet() { }

    public static final int PROTOCOL = 3;
    public static final int TO_CLIENT_CHUNK = 60_000;
    /** how many pieces may be on their way at once. The next ones wait until the guest says earlier ones arrived. */
    public static final int WINDOW = 6;
    public static final int TO_SERVER_CHUNK = 24_000;   // the game refuses bigger messages from clients
    public static final String MANIFEST_ENTRY = "__twp_manifest.json";

    private static Identifier channel(String path) { return Identifier.fromNamespaceAndPath("twoweekphase", path); }

    /** host -> guest: "this is the Two Week Phase world of these two owners". */
    public record Welcome(String json) implements CustomPacketPayload {
        public static final Type<Welcome> TYPE = new Type<>(channel("welcome"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Welcome> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(262144), Welcome::json, Welcome::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** guest -> host: what the guest's copy looks like (sent in pieces). */
    public record Part(int id, int index, int total, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Part> TYPE = new Type<>(channel("part"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Part> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Part::id, ByteBufCodecs.VAR_INT, Part::index, ByteBufCodecs.VAR_INT, Part::total,
                ByteBufCodecs.byteArray(TO_SERVER_CHUNK + 64), Part::bytes, Part::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** host -> guest: a piece of the zip holding the files that changed. */
    public record Data(int id, int index, int total, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(channel("data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Data::id, ByteBufCodecs.VAR_INT, Data::index, ByteBufCodecs.VAR_INT, Data::total,
                ByteBufCodecs.byteArray(TO_CLIENT_CHUNK + 64), Data::bytes, Data::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** guest -> host: "that piece arrived". Keeps a big world from flooding a slow connection. */
    public record Ack(int id, int index) implements CustomPacketPayload {
        public static final Type<Ack> TYPE = new Type<>(channel("ack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ack> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ack::id, ByteBufCodecs.VAR_INT, Ack::index, Ack::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** host -> guest: "that was all of it", with a checksum. */
    public record Done(String json) implements CustomPacketPayload {
        public static final Type<Done> TYPE = new Type<>(channel("done"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Done> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(32767), Done::json, Done::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** host -> guest: "you hold the newer world. Open yours; I will join you and take your progress." (the text is the world's id) */
    public record Swap(String lineage) implements CustomPacketPayload {
        public static final Type<Swap> TYPE = new Type<>(channel("swap"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Swap> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(64), Swap::lineage, Swap::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void registerTypes() {
        PayloadTypeRegistry.clientboundPlay().register(Swap.TYPE, Swap.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Welcome.TYPE, Welcome.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Data.TYPE, Data.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Done.TYPE, Done.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Part.TYPE, Part.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Ack.TYPE, Ack.CODEC);
    }

    // ---- the JSON inside those messages

    public static final class WelcomeMsg {
        public int protocol = PROTOCOL;
        public TwpFiles.Owners owners;
        public int[] sessions;
    }

    public static final class RequestMsg {
        public String lineage;
        public boolean haveWorld;
        /** true when this guest has agreed that the host's world replaces what its own copy holds */
        public boolean yield;
        public int[] sessions;
        public Map<String, long[]> files;
    }

    /** Stored inside the zip: the host's full file list, so the guest can remove files that no longer exist. */
    public static final class TransferManifest {
        public int[] sessions;
        public Map<String, long[]> files;
    }

    public static final class DoneMsg {
        public int id;
        public long size;
        public String sha1;
    }
}
