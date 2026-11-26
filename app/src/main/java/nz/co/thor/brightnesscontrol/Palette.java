package nz.co.thor.brightnesscontrol;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;

/**
 * Catppuccin Macchiato (https://catppuccin.com/palette), with an optional
 * AMOLED variant that swaps the background layers for true black while
 * keeping the accent and text colours.
 */
final class Palette {
    // Accents
    static final int ROSEWATER = 0xFFF4DBD6;
    static final int MAUVE = 0xFFC6A0F6;
    static final int RED = 0xFFED8796;
    static final int PEACH = 0xFFF5A97F;
    static final int YELLOW = 0xFFEED49F;
    static final int GREEN = 0xFFA6DA95;
    static final int TEAL = 0xFF8BD5CA;
    static final int SKY = 0xFF91D7E3;
    static final int BLUE = 0xFF8AADF4;
    static final int LAVENDER = 0xFFB7BDF8;
    // Text
    static final int TEXT = 0xFFCAD3F5;
    static final int SUBTEXT1 = 0xFFB8C0E0;
    static final int SUBTEXT0 = 0xFFA5ADCB;
    static final int OVERLAY1 = 0xFF8087A2;
    static final int OVERLAY0 = 0xFF6E738D;

    // Layers (reassigned for AMOLED)
    static int surface2;
    static int surface1;
    static int surface0;
    static int base;
    static int mantle;
    static int crust;
    static boolean amoled;

    private Palette() { }

    static void load(SharedPreferences prefs) {
        amoled = prefs.getBoolean(Prefs.AMOLED, false);
        if (amoled) {
            surface2 = 0xFF3A3D52;
            surface1 = 0xFF262838;
            surface0 = 0xFF15161F;
            base = Color.BLACK;
            mantle = Color.BLACK;
            crust = Color.BLACK;
        } else {
            surface2 = 0xFF5B6078;
            surface1 = 0xFF494D64;
            surface0 = 0xFF363A4F;
            base = 0xFF24273A;
            mantle = 0xFF1E2030;
            crust = 0xFF181926;
        }
    }

    /** Text that sits on a filled accent (mauve pill, etc.). */
    static int onAccent() {
        return 0xFF181926;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Soft tinted fill for status buttons: green when ready, peach when needed. */
    static ColorStateList statusFill(boolean ok) {
        return ColorStateList.valueOf(withAlpha(ok ? GREEN : PEACH, 0x38));
    }

    static int statusText(boolean ok) {
        return ok ? GREEN : PEACH;
    }
}
