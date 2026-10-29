package com.example.gulp;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Asked once on first join when Flamevore is installed: which mod should do the swallowing? Also opened from Settings. */
public class SwallowChoiceScreen extends Screen {
    private static final int W = 300;
    private static final int H = 176;
    private static final int COL_W = 130;

    private static final String GULP_TEXT =
            "Press V to swallow mobs and players. Your belly grows and everything uses your stomach level and perks.";
    private static final String FLAMEVORE_TEXT =
            "Flamevore does the swallowing. Gulp's mob and player eating and belly growth are switched off, "
            + "but item swallowing, your level, perks and item storage stay.";

    private List<FormattedCharSequence> gulpLines = List.of();
    private List<FormattedCharSequence> flamevoreLines = List.of();

    public SwallowChoiceScreen() {
        super(Component.literal("Swallowing system"));
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    protected void init() {
        int x = left();
        int y = top();
        gulpLines = font.split(Component.literal(GULP_TEXT), COL_W);
        flamevoreLines = font.split(Component.literal(FLAMEVORE_TEXT), COL_W);

        addRenderableWidget(Button.builder(Component.literal("Gulp"), btn -> choose(SwallowSystem.GULP))
                .bounds(x + 15, y + 44, COL_W, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Flamevore"), btn -> choose(SwallowSystem.FLAMEVORE))
                .bounds(x + W - 15 - COL_W, y + 44, COL_W, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Decide later"), btn -> onClose())
                .bounds(x + (W - 90) / 2, y + H - 28, 90, 20).build());
    }

    private void choose(SwallowSystem system) {
        Net.send(19, system.ordinal());
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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

        g.drawString(font, "Choose your swallowing system", x + 8, y + 8, 0xFFFFFF, true);
        g.drawString(font, "You can switch any time in Settings. Your level, perks", x + 8, y + 22, 0x999999, false);
        g.drawString(font, "and item storage are kept either way.", x + 8, y + 31, 0x999999, false);

        for (int i = 0; i < gulpLines.size(); i++) {
            g.drawString(font, gulpLines.get(i), x + 15, y + 70 + i * 9, 0xBBBBBB, false);
        }
        for (int i = 0; i < flamevoreLines.size(); i++) {
            g.drawString(font, flamevoreLines.get(i), x + W - 15 - COL_W, y + 70 + i * 9, 0xBBBBBB, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }
}
