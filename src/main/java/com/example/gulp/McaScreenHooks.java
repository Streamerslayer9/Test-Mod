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
 * Adds Gulp's bits to MCA's villager screen without touching MCA: a "Type: ..." box and a small level bar in the bottom-left
 * corner (below the chat lines, left of the hotbar), and a Perks button just left of the Talk button, for every villager.
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
    private static final int BOTTOM_DY = 36; // our Type box's top edge sits this far above the bottom of the window: below the chat lines
    private static final int BAR_DY = 18;   // from the Type box down to the level bar
    private static final int BAR_H = 5;     // the bar is as wide as the Type box (see onRender), this is how tall
    private static final int PERKS_GAP = 3; // between the Perks button and Talk

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
        Font font = Minecraft.getInstance().font;
        GuiGraphics g = e.getGuiGraphics();
        if (perksButton != null) {
            perksButton.render(g, e.getMouseX(), e.getMouseY(), e.getPartialTick());
        }
        if (info == null) return;
        String kind = info.type == 2 ? "Pred" : info.type == 1 ? "Prey" : "Normal";
        int typeY = e.getScreen().height - BOTTOM_DY;
        int boxW = box(g, font, "Type: " + kind, LEFT_X, typeY);
        // every villager has a level (1 to 10) and a bar
        int by = typeY + BAR_DY;
        boolean max = info.level >= VillagerHolding.MAX_LEVEL;
        float frac = max ? 1f : Math.min(1f, info.xp / (float) Math.max(1, info.xpNeeded));
        g.fill(LEFT_X - 1, by - 1, LEFT_X + boxW + 1, by + BAR_H + 1, 0xFF000000);
        g.fill(LEFT_X, by, LEFT_X + boxW, by + BAR_H, 0xFF222222);
        g.fill(LEFT_X, by, LEFT_X + (int) (boxW * frac), by + BAR_H, 0xFFE6C84A);
        g.drawString(font, "Lv " + info.level, LEFT_X + boxW + 5, by - 2, 0xFFFFFF, true);
    }
}
