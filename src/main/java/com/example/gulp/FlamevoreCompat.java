package com.example.gulp;

import net.minecraftforge.fml.ModList;

/**
 * Flamevore compatibility. Flamevore is a separate mod we never touch: we only ask whether it is installed and,
 * later, read what it does and react to it. Nothing here needs Flamevore at compile time.
 */
public final class FlamevoreCompat {
    private FlamevoreCompat() { }

    /** Flamevore's mod id. Confirm it against Flamevore's META-INF/mods.toml: if it is wrong, Gulp just behaves as if Flamevore isn't there. */
    public static final String MOD_ID = "flamevore";

    private static Boolean loaded;

    /** @return true if Flamevore is installed (the answer is the same for the whole session, so it is remembered) */
    public static boolean isLoaded() {
        if (loaded == null) {
            ModList list = ModList.get();
            if (list == null) return false; // too early to know; ask again next time
            loaded = list.isLoaded(MOD_ID);
        }
        return loaded;
    }
}
