package com.example.gulp;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * A villager's perks, opened from the Perks button on the MCA villager screen (every villager has one). Read only: every
 * villager has a level and every adult villager earns XP: from talking, gifts and rides in a stomach (see VillagerXp) and,
 * for preds, from comforting players (see VillagerHolding). The comfort perks below come with the level and only do
 * anything for pred villagers.
 * Page 1 is the real perks. When PREVIEW_PERKS is on, pages 2 and 3 show made-up placeholder perks that do nothing at all:
 * they exist only so the layout can be looked at, and need no server or save data. Turn PREVIEW_PERKS off to remove them.
 */
public class VillagerPerksScreen extends Screen {
    private static final int W = 270;
    private static final int H = 196;
    private static final int FEED_LEVEL = 5; // the level that starts feeding the player (see VillagerHolding.tick)
    private static final int ROW_H = 32;
    private static final int ROW_STEP = 35;

    /** Preview only: show the placeholder pages below. They have no effect in the game. */
    private static final boolean PREVIEW_PERKS = true;
    // name, description, unlock text; three per page
    private static final String[][] DUMMY = {
            {"Soothing aura", "Placeholder: calms nearby villagers.", "Unlocks at level 2."},
            {"Warm lullaby", "Placeholder: helps you rest faster.", "Unlocks at level 3."},
            {"Gentle grip", "Placeholder: struggling hurts less.", "Unlocks at level 4."},
            {"Bottomless belly", "Placeholder: holds more at once.", "Unlocks at level 6."},
            {"Second wind", "Placeholder: refills your stamina.", "Unlocks at level 7."},
            {"Lucky charm", "Placeholder: better loot from gifts.", "Unlocks at level 8."},
    };

    private final Screen parent;
    private final int entityId;
    private final String villagerName;
    private int page; // 0 = real perks, 1 and 2 = preview placeholders

    public VillagerPerksScreen(Screen parent, int entityId, String villagerName) {
        super(Component.literal("Villager perks"));
        this.parent = parent;
        this.entityId = entityId;
        this.villagerName = villagerName;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    protected void init() {
        Net.send(20, entityId); // ask for fresh numbers
        int x = left();
        int y = top();
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> this.minecraft.setScreen(parent))
                .bounds(x + 8, y + H - 28, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), btn -> this.minecraft.setScreen(null))
                .bounds(x + W - 68, y + H - 28, 60, 20).build());
        if (PREVIEW_PERKS) {
            addRenderableWidget(Button.builder(Component.literal("<"), btn -> page = Math.max(0, page - 1))
                    .bounds(x + W / 2 - 24, y + H - 28, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), btn -> page = Math.min(pageCount() - 1, page + 1))
                    .bounds(x + W / 2 + 4, y + H - 28, 20, 20).build());
        }
    }

    private int pageCount() {
        return PREVIEW_PERKS ? 1 + DUMMY.length / 3 : 1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static String num(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void perkRow(GuiGraphics g, int x, int y, String name, String line1, String line2, boolean unlocked) {
        g.fill(x + 8, y, x + W - 8, y + ROW_H, 0x40FFFFFF);
        g.drawString(font, name, x + 14, y + 3, unlocked ? 0xFFFFFF : 0x888888, true);
        g.drawString(font, line1, x + 14, y + 13, unlocked ? 0xBBBBBB : 0x777777, false);
        g.drawString(font, line2, x + 14, y + 22, 0x888888, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int x = left();
        int y = top();

        g.fill(x, y, x + W, y + H, 0xE6141414);
        g.fill(x, y, x + W, y + 1, 0xFF666666);
        g.fill(x, y + H - 1, x + W, y + H, 0xFF666666);
        g.fill(x, y, x + 1, y + H, 0xFF666666);
        g.fill(x + W - 1, y, x + W, y + H, 0xFF666666);

        g.drawString(font, villagerName + " - Perks", x + 8, y + 8, 0xFFFFFF, true);

        VillagerInfoPacket info = ClientVillagerInfo.get(entityId);
        if (page > 0) {
            g.drawString(font, "Preview perks (placeholders, they do nothing)", x + 8, y + 24, 0xE6C84A, false);
            int row = y + 40;
            for (int i = 0; i < 3; i++) {
                String[] d = DUMMY[(page - 1) * 3 + i];
                perkRow(g, x, row + i * ROW_STEP, d[0], d[1], d[2], false);
            }
        } else if (info == null) {
            g.drawString(font, "Loading...", x + 8, y + 28, 0xAAAAAA, false);
        } else {
            int level = info.level;
            boolean max = level >= VillagerHolding.MAX_LEVEL;
            g.drawString(font, "Level " + level + (max ? " (max)" : ""), x + 8, y + 24, 0xE6C84A, false);
            if (info.type != 2) {
                String note = "Comfort perks work for preds only";
                g.drawString(font, note, x + W - 8 - font.width(note), y + 24, 0x999999, false);
            }
            int bx = x + 8;
            int by = y + 36;
            int bw = W - 16;
            g.fill(bx - 1, by - 1, bx + bw + 1, by + 7, 0xFF000000);
            g.fill(bx, by, bx + bw, by + 6, 0xFF222222);
            float frac = max ? 1f : Math.min(1f, info.xp / (float) Math.max(1, info.xpNeeded));
            g.fill(bx, by, bx + (int) (bw * frac), by + 6, 0xFFE6C84A);
            if (!max) g.drawString(font, info.xp + " / " + info.xpNeeded + " XP to the next level", bx, by + 10, 0x999999, false);

            int row = y + 56;
            String healNext = max ? "At the top level." : "Next level: " + num(VillagerHolding.healPerSecond(level + 1)) + " HP per second.";
            perkRow(g, x, row, "Healing touch",
                    "Heals you " + num(VillagerHolding.healPerSecond(level)) + " HP every second.", healNext, true);

            String holdNext = max ? "At the top level." : "Next level: " + (VillagerHolding.maxTicks(level + 1) / 20) + " seconds.";
            perkRow(g, x, row + ROW_STEP, "Long embrace",
                    "Holds you for up to " + (VillagerHolding.maxTicks(level) / 20) + " seconds.", holdNext, true);

            boolean feeds = level >= FEED_LEVEL;
            perkRow(g, x, row + 2 * ROW_STEP, "Feeding",
                    feeds ? "Restores a little hunger while holding you." : "Unlocks at level " + FEED_LEVEL + ".",
                    "XP: talking, gifts, rides and comforting.", feeds);
        }

        if (PREVIEW_PERKS) {
            String label = (page + 1) + " / " + pageCount();
            g.drawCenteredString(font, label, left() + W / 2, top() + H - 37, 0x999999);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
