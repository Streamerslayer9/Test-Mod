package com.example.gulp;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: what kind of villager this is and their comfort level, for the MCA villager screen (asked for with action 20). */
public class VillagerInfoPacket {
    public final int entityId;
    /** 0 normal, 1 prey, 2 pred. */
    public final int type;
    /** VillagerTrait ordinal: 0 none, 1 shy, 2 confident. */
    public final int trait;
    public final int level;
    public final int xp;
    public final int xpNeeded;

    public VillagerInfoPacket(int entityId, int type, int trait, int level, int xp, int xpNeeded) {
        this.entityId = entityId;
        this.type = type;
        this.trait = trait;
        this.level = level;
        this.xp = xp;
        this.xpNeeded = xpNeeded;
    }

    public static void encode(VillagerInfoPacket m, FriendlyByteBuf buf) {
        buf.writeInt(m.entityId);
        buf.writeByte(m.type);
        buf.writeByte(m.trait);
        buf.writeByte(m.level);
        buf.writeInt(m.xp);
        buf.writeInt(m.xpNeeded);
    }

    public static VillagerInfoPacket decode(FriendlyByteBuf buf) {
        return new VillagerInfoPacket(buf.readInt(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readInt(), buf.readInt());
    }

    public static void handle(VillagerInfoPacket m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> ClientVillagerInfo.put(m));
        c.setPacketHandled(true);
    }
}
