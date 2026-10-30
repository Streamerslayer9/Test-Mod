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
 * Adds Gulp's bits to MCA's villager screen without touching MCA: a "Type: Pred" box and a small comfort-level bar on the left
 * under MCA's row of icons (clear of the info boxes and traits above them), and a Perks button just left of the Talk button.
 * The Perks button is added as an event listener only (clickable but not drawn by the screen), so it is drawn here by hand.
 * The positions are measured from MCA's own Talk / Interact buttons, so tweak the offsets below if they land a little off.
 * Anything unexpected on the screen just means nothing is added.
 */
@Mod.EventBusSubscriber(modid = Gulp.ID, value = Dist.CLIENT)
public final class McaScreenHooks {
    private McaScreenHooks() { }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean logged;

    private static final int LEFT_X = 18;   // left edge of MCA's info boxes
    private static final int TYPE_DY = 196; // from the top of the Talk button down to our Type box: under MCA's row of icons
    private static final int ICON_GAP = 6;  // minimum space between MCA's lowest left-hand icon and our Type box
    private static final int BAR_DY = 19;   // from the Type box down to the level bar
    private static final int BAR_H = 5;     // the bar is as wide as the Type box (see onRender), this is how tall
    private static final int PERKS_GAP = 3; // between the Perks button and Talk
    /** Preview: show the Perks button on every villager (not only preds) so its place can be checked. Set to false to ship. */
    private static final boolean PREVIEW_PERKS_BUTTON = true;

    private static Screen shownScreen;
    private static LivingEntity villager;
    private static int topY;
    private static int iconsBottom; // bottom edge of the lowest widget in MCA's left-hand icon column, 0 if none were found
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
        // if MCA's icons are widgets, keep our Type box clear of the lowest one wherever it sits
        iconsBottom = 0;
        for (GuiEventListener l : e.getListenersList()) {
            if (l instanceof AbstractWidget w && w.visible
                    && w.getX() + w.getWidth() > LEFT_X && w.getX() < LEFT_X + 70 && w.getY() > topY + 40) {
                iconsBottom = Math.max(iconsBottom, w.getY() + w.getHeight());
            }
        }
        int step = interact.getY() - talk.getY();
        // only on the main menu, which has a big gap above the last button (Family Tree); the sub-menus are evenly spaced
        boolean mainMenu = false;
        for (int i = 1; i < right.size(); i++) {
            if (right.get(i).getY() - right.get(i - 1).getY() >= step * 2) mainMenu = true;
        }
        if (mainMenu && step > 0) {
            int width = talk.getWidth() * 3 / 4;
            int id = v.getId();
            String name = v.getName().getString();
            perksButton = Button.builder(Component.literal("Perks"),
                            btn -> mc.setScreen(new VillagerPerksScreen(screen, id, name)))
                    .bounds(talk.getX() - width - PERKS_GAP, talk.getY(), width, talk.getHeight()).build();
            perksButton.visible = false; // shown once the server says this villager is a pred
            perksButton.active = false;
            e.addListener(perksButton);
        }
    }

    /** Draws the dark purple label box and returns its width. */
    private static int box(GuiGraphics g, Font font, String text, int x, int y) {
        int w = font.width(text) + 8;
        g.fill(x, y, x + w, y + 16, 0xF0100010);
        g.fill(x, y, x + w, y + 1, 0xFF3C1A78);
        g.fill(x, y + 15, x + w, y + 16, 0xFF3C1A78);
        g.fill(x, y, x + 1, y + 16, 0xFF3C1A78);
        g.fill(x + w - 1, y, x + w, y + 16, 0xFF3C1A78);
        g.drawString(font, text, x + 4, y + 4, 0xFFFFFF, false);
        return w;
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post e) {
        if (e.getScreen() != shownScreen || villager == null) return;
        VillagerInfoPacket info = ClientVillagerInfo.get(villager.getId());
        boolean pred = info != null && info.type == 2;
        Font font = Minecraft.getInstance().font;
        GuiGraphics g = e.getGuiGraphics();
        if (perksButton != null) {
            boolean show = pred || PREVIEW_PERKS_BUTTON;
            perksButton.visible = show;
            perksButton.active = show;
            perksButton.render(g, e.getMouseX(), e.getMouseY(), e.getPartialTick());
        }
        if (info == null) return;
        String kind = info.type == 2 ? "Pred" : info.type == 1 ? "Prey" : "Normal";
        int wanted = Math.max(topY + TYPE_DY, iconsBottom > 0 ? iconsBottom + ICON_GAP : 0);
        int typeY = Math.min(wanted, e.getScreen().height - 45); // stays on a small screen
        int boxW = box(g, font, "Type: " + kind, LEFT_X, typeY);
        if (pred) {
            int by = typeY + BAR_DY;
            boolean max = info.level >= VillagerHolding.MAX_LEVEL;
            float frac = max ? 1f : Math.min(1f, info.xp / (float) Math.max(1, info.xpNeeded));
            g.fill(LEFT_X - 1, by - 1, LEFT_X + boxW + 1, by + BAR_H + 1, 0xFF000000);
            g.fill(LEFT_X, by, LEFT_X + boxW, by + BAR_H, 0xFF222222);
            g.fill(LEFT_X, by, LEFT_X + (int) (boxW * frac), by + BAR_H, 0xFFE6C84A);
            g.drawString(font, "Lv " + info.level, LEFT_X + boxW + 5, by - 2, 0xFFFFFF, true);
        }
    }
}
