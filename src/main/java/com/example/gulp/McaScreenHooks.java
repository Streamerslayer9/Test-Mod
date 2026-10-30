package com.example.gulp;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Adds Gulp's bits to MCA's villager screen without touching MCA: a "Type: Pred" box and a comfort-level bar under the
 * villager's info on the left, and a Perks button between Interact and Family Tree on the right.
 * The positions are measured from MCA's own Talk / Interact buttons (nothing is hard-wired to the window size), so tweak the
 * three offsets below if the boxes land a little off. Anything unexpected on the screen just means nothing is added.
 */
@Mod.EventBusSubscriber(modid = Gulp.ID, value = Dist.CLIENT)
public final class McaScreenHooks {
    private McaScreenHooks() { }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean logged;

    private static final int LEFT_X = 18;   // left edge of MCA's info boxes
    private static final int TYPE_DY = 73;  // from the top of the right-hand buttons (Talk) down to our Type box
    private static final int BAR_DY = 91;   // ... down to the level bar
    private static final int BAR_W = 100;

    private static Screen shownScreen;
    private static LivingEntity villager;
    private static int topY;
    private static Button perksButton;

    /** MCA's villager screen: its class is called InteractScreen and lives in an mca package. */
    private static boolean isInteractScreen(Screen screen) {
        String name = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("mca") && name.endsWith("interactscreen");
    }

    /** The villager the screen is about: the first living entity in one of the screen's fields that isn't the player. */
    private static LivingEntity findVillager(Screen screen, Minecraft mc) {
        for (Class<?> c = screen.getClass(); c != null && c != Screen.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(screen) instanceof LivingEntity le && le != mc.player) return le;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // not readable: try the next field
                }
            }
        }
        return mc.crosshairPickEntity instanceof LivingEntity le ? le : null; // what you right-clicked
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post e) {
        Screen screen = e.getScreen();
        if (!isInteractScreen(screen)) {
            if (screen == shownScreen) shownScreen = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LivingEntity v = findVillager(screen, mc);
        shownScreen = null;
        perksButton = null;
        if (v == null) return;
        villager = v;
        shownScreen = screen;
        Net.send(20, v.getId()); // ask the server for their type and level

        // MCA's right-hand column of buttons, top to bottom: Talk, Interact, (room for ours), Family Tree
        List<AbstractWidget> right = new ArrayList<>();
        for (GuiEventListener l : e.getListenersList()) {
            if (l instanceof AbstractWidget w && w.visible && w.getX() >= screen.width * 0.6) right.add(w);
        }
        right.sort(Comparator.comparingInt(AbstractWidget::getY));
        if (!logged) { // once, so there is something to look at in latest.log if the layout is off
            logged = true;
            LOGGER.info("[Gulp] MCA villager screen {}: villager '{}', {} buttons on the right, window {}x{}", screen.getClass().getName(),
                    v.getName().getString(), right.size(), screen.width, screen.height);
        }
        if (right.size() < 2) return;
        AbstractWidget talk = right.get(0);
        AbstractWidget interact = right.get(1);
        topY = talk.getY();
        int step = interact.getY() - talk.getY();
        // only on the main menu: three buttons with a free slot between Interact and the last one
        if (right.size() == 3 && step > 0 && right.get(2).getY() >= interact.getY() + step + interact.getHeight()) {
            int id = v.getId();
            String name = v.getName().getString();
            perksButton = Button.builder(Component.literal("Perks"),
                            btn -> mc.setScreen(new VillagerPerksScreen(screen, id, name)))
                    .bounds(interact.getX(), interact.getY() + step, interact.getWidth(), interact.getHeight()).build();
            perksButton.visible = false; // shown once the server says this villager is a pred
            perksButton.active = false;
            e.addListener(perksButton);
        }
    }

    private static void box(GuiGraphics g, Font font, String text, int x, int y) {
        int w = font.width(text) + 8;
        g.fill(x, y, x + w, y + 16, 0xF0100010);
        g.fill(x, y, x + w, y + 1, 0xFF3C1A78);
        g.fill(x, y + 15, x + w, y + 16, 0xFF3C1A78);
        g.fill(x, y, x + 1, y + 16, 0xFF3C1A78);
        g.fill(x + w - 1, y, x + w, y + 16, 0xFF3C1A78);
        g.drawString(font, text, x + 4, y + 4, 0xFFFFFF, false);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post e) {
        if (e.getScreen() != shownScreen || villager == null) return;
        VillagerInfoPacket info = ClientVillagerInfo.get(villager.getId());
        if (info == null) return;
        boolean pred = info.type == 2;
        if (perksButton != null) {
            perksButton.visible = pred;
            perksButton.active = pred;
        }
        Font font = Minecraft.getInstance().font;
        GuiGraphics g = e.getGuiGraphics();
        String kind = info.type == 2 ? "Pred" : info.type == 1 ? "Prey" : "Normal";
        box(g, font, "Type: " + kind, LEFT_X, topY + TYPE_DY);
        if (pred) {
            int by = topY + BAR_DY;
            boolean max = info.level >= VillagerHolding.MAX_LEVEL;
            float frac = max ? 1f : Math.min(1f, info.xp / (float) Math.max(1, info.xpNeeded));
            g.fill(LEFT_X - 1, by - 1, LEFT_X + BAR_W + 1, by + 7, 0xFF000000);
            g.fill(LEFT_X, by, LEFT_X + BAR_W, by + 6, 0xFF222222);
            g.fill(LEFT_X, by, LEFT_X + (int) (BAR_W * frac), by + 6, 0xFFE6C84A);
            g.drawString(font, "Lv " + info.level, LEFT_X + BAR_W + 6, by - 1, 0xFFFFFF, true);
        }
    }
}
