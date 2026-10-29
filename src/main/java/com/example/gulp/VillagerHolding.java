package com.example.gulp;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A pred villager holding a player to comfort them. Only ever soft and only ever by the player's say-so:
 * the villager offers to a hurt player whose role can be swallowed (Prey or Both), and the player has to press Accept.
 *
 * It works like PlayerHolding does for a player swallower: the held player is put in spectator mode looking through
 * the villager's eyes (so they can't interact with anything), and their old game mode is remembered, including in
 * their saved data so a crash can never leave anyone stuck in spectator mode. The player gets 1 HP back every second
 * and is let out when they are healed, after a minute, or the moment they press Release (G). They are also let out at
 * once if the villager is hurt, dies or goes away, or when the player logs out or the server stops.
 * Struggle (R) rubs the walls: it raises the villager's hearts a little (not more than once every 20 seconds).
 */
public final class VillagerHolding {
    private VillagerHolding() { }

    private static final int MAX_TICKS = 60 * 20; // a minute at most
    private static final int RUB_COOLDOWN = 20 * 20;

    private static final class Hold {
        final UUID villager;
        final int previousMode;
        int ticks;
        int rubCd;

        Hold(UUID villager, int previousMode) {
            this.villager = villager;
            this.previousMode = previousMode;
        }
    }

    /** player -> the villager holding them. Only lives while the server runs. */
    private static final Map<UUID, Hold> HELD = new HashMap<>();

    public static boolean isHeld(ServerPlayer p) { return HELD.containsKey(p.getUUID()); }

    /** Starts holding the player. The caller has already checked the player agreed and can be held. */
    public static boolean hold(ServerPlayer p, LivingEntity villager) {
        if (PlayerHolding.isHeld(p) || !p.isAlive()) return false;
        int previous = p.gameMode.getGameModeForPlayer().getId();
        HELD.put(p.getUUID(), new Hold(villager.getUUID(), previous));
        p.getPersistentData().putInt(PlayerHolding.KEY_PREV_MODE, previous); // survives a crash
        p.setGameMode(GameType.SPECTATOR);
        p.setCamera(villager);
        p.sendSystemMessage(Component.empty().append(villager.getName()).append(
                " is holding you in their tummy to help you heal. Press your Release key (default G) to get out whenever you like,"
                + " or your Struggle key (default R) to rub the walls."));
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new HeldPacket(1, villager.getName().getString(), 0f, 0f));
        return true;
    }

    /** Every tick for every player (called from PlayerHolding.tickHeld). Heals, and lets the player out when it is time or something is wrong. */
    static void tick(ServerPlayer p) {
        Hold h = HELD.get(p.getUUID());
        if (h == null) return;
        h.ticks++;
        if (h.rubCd > 0) h.rubCd--;
        Entity e = p.serverLevel().getEntity(h.villager);
        if (!(e instanceof LivingEntity v) || !v.isAlive() || v.hurtTime > 0) {
            release(p, "The villager had to let you out.", false);
            return;
        }
        if (p.getCamera() != v) p.setCamera(v); // pressing sneak would make a spectator stop looking through their eyes
        if (h.ticks % 20 == 0) p.heal(1.0f);
        if (p.getHealth() >= p.getMaxHealth()) {
            release(p, "All better. You were let out.", true);
        } else if (h.ticks >= MAX_TICKS) {
            release(p, "You were let out.", true);
        }
    }

    /** The Release key. Always works: this is a gentle rest, never a trap. */
    static void tryRelease(ServerPlayer p) {
        release(p, "You climbed out.", false);
    }

    /** The Struggle key: rub the walls. The villager is pleased (a few hearts), at most once every 20 seconds. */
    static void rub(ServerPlayer p) {
        Hold h = HELD.get(p.getUUID());
        if (h == null) return;
        if (h.rubCd > 0) {
            StomachLogic.say(p, Component.literal("Give it a moment before you rub again."));
            return;
        }
        h.rubCd = RUB_COOLDOWN;
        Entity e = p.serverLevel().getEntity(h.villager);
        if (e != null) McaIntegration.addHearts(e, p, 2);
        StomachLogic.say(p, Component.literal("You rub the walls gently. They seem pleased."));
    }

    /**
     * Lets the player out and gives them their game mode back, putting them down in front of the villager.
     * @param thanked true when it ended on its own (healed or time up): the villager is pleased with that
     */
    static void release(ServerPlayer p, String message, boolean thanked) {
        Hold h = HELD.remove(p.getUUID());
        if (h == null) return;
        Entity villager = p.serverLevel().getEntity(h.villager);
        GameType mode = GameType.byId(h.previousMode);
        if (mode == GameType.SPECTATOR) mode = GameType.SURVIVAL; // never leave anyone stuck as a spectator
        p.setCamera(p);
        p.setGameMode(mode);
        p.getPersistentData().remove(PlayerHolding.KEY_PREV_MODE);
        if (villager != null && villager.level() instanceof ServerLevel w) {
            Vec3 fwd = villager.getViewVector(1.0f).scale(1.5);
            double x = villager.getX() + fwd.x;
            double y = villager.getY();
            double z = villager.getZ() + fwd.z;
            AABB box = p.getBoundingBox().move(x - p.getX(), y - p.getY(), z - p.getZ());
            if (!w.noCollision(box)) { // no room in front of them: use the spot they're standing on
                x = villager.getX();
                y = villager.getY();
                z = villager.getZ();
            }
            p.teleportTo(w, x, y, z, villager.getYRot() + 180f, 0f);
            if (thanked) McaIntegration.addHearts(villager, p, 2);
        }
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new HeldPacket(0, "", 0f, 0f));
        p.sendSystemMessage(Component.literal(message));
    }

    /** The server is stopping: let everyone out first. */
    static void releaseAll(MinecraftServer server) {
        for (UUID id : new ArrayList<>(HELD.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) release(p, "You were let out.", false);
        }
        HELD.clear();
    }
}
