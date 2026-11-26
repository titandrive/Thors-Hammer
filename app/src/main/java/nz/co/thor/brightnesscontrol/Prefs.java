package nz.co.thor.brightnesscontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

final class Prefs {
    static final String FILE = "brightness_control";
    static final String ENABLED = "enabled";
    static final String MODIFIER = "modifier_key";
    static final String STEP = "step";
    static final String PRESS_STEP = "press_step";
    static final String HOLD_STEP = "hold_step";
    static final String LINK_HOLD_STEP = "link_hold_step";
    static final String REPEAT_DELAY = "repeat_delay";
    static final String GENTLE_WAKE = "gentle_wake";
    static final String GENTLE_WAKE_DURATION = "gentle_wake_duration";
    static final String WAKE_RESET_TIMEOUT = "wake_reset_timeout";
    static final String WAKE_RESET_BRIGHTNESS = "wake_reset_brightness";
    static final String WAKE_CLOSED_AT = "wake_closed_at";
    static final String WAKE_HOLD_DURATION = "wake_hold_duration";
    static final String WAKE_RESET_ENABLED = "wake_reset_enabled";
    static final String WAKE_TOP = "wake_top";
    static final String WAKE_BOTTOM = "wake_bottom";
    static final String CONSUME_MODIFIER = "consume_modifier";
    static final String CAPTURING = "capturing";
    static final String TARGET = "target";
    static final String THEME = "theme";
    static final String AMOLED = "amoled";
    static final String UP_KEY = "up_key";
    static final String DOWN_KEY = "down_key";
    // Binding B: an optional second modifier + Brighter/Dimmer set with its
    // own screen target (e.g. R1+volume = top, L1+volume = bottom).
    static final String B_ENABLED = "binding_b_enabled";
    static final String B_MODIFIER = "binding_b_modifier_key";
    static final String B_UP_KEY = "binding_b_up_key";
    static final String B_DOWN_KEY = "binding_b_down_key";
    static final String B_TARGET = "binding_b_target";
    static final String B_PRESS_STEP = "binding_b_press_step";
    static final String B_HOLD_STEP = "binding_b_hold_step";
    // Binding B's D-pad / stick directions (recorded through root or Shizuku).
    static final String ROOT_B_BRIGHT_SOURCE = "root_b_bright_source";
    static final String ROOT_B_BRIGHT_DIRECTION = "root_b_bright_direction";
    static final String ROOT_B_DIM_SOURCE = "root_b_dim_source";
    static final String ROOT_B_DIM_DIRECTION = "root_b_dim_direction";
    // Volume (adapted from Thor Volume Link). Off by default so volume keys
    // behave exactly as before until the user turns it on.
    static final String VOL_ENABLED = "vol_enabled";
    static final String VOL_MODE = "vol_mode";
    static final String VOL_STEP = "vol_step";
    static final String VOL_KEEP_BALANCE = "vol_keep_balance";
    static final String VOL_BALANCE = "vol_balance";
    static final String VOL_AUTO_FOLLOW = "vol_auto_follow";
    static final String VOL_SHOW_PANEL = "vol_show_panel";
    static final String UI_TAB = "ui_tab";
    static final int VOL_MODE_PRIMARY = 0;
    static final int VOL_MODE_SECONDARY = 1;
    static final int VOL_MODE_LINKED = 2;
    static final int VOL_MODE_FOCUS = 3;
    static final String ROOT_AXES = "root_axes";
    static final String AXIS_SOURCE = "axis_source";
    static final String ROOT_BRIGHT_DIRECTION = "root_bright_direction";
    static final String ROOT_DIM_DIRECTION = "root_dim_direction";
    static final String ROOT_BRIGHT_SOURCE = "root_bright_source";
    static final String ROOT_DIM_SOURCE = "root_dim_source";
    static final String ROOT_MODIFIER_SOURCE = "root_modifier_source";
    static final String ROOT_MODIFIER_DIRECTION = "root_modifier_direction";
    static final int ROOT_DIRECTION_UP = 1;
    static final int ROOT_DIRECTION_DOWN = 2;
    static final int ROOT_DIRECTION_LEFT = 3;
    static final int ROOT_DIRECTION_RIGHT = 4;
    static final String ROOT_LIMIT_ACK = "root_limit_ack";
    static final String SETUP_GUIDE_SHOWN = "setup_guide_shown";
    static final String AWAITING_STEP_TWO = "awaiting_step_two";
    static final String BRIGHTNESS_GUIDE_SHOWN = "brightness_guide_shown";
    static final String KEY_GUIDE_SHOWN = "key_guide_shown";
    static final String ADVANCE_TO_KEY_PENDING = "advance_to_key_pending";
    static final String ADVANCE_TO_BRIGHTNESS_PENDING = "advance_to_brightness_pending";
    static final String SETUP_COMPLETE_SHOWN = "setup_complete_shown";
    static final String CONFLICT_WARNING_SHOWN = "conflict_warning_shown";
    static final String CONFLICT_WARNING_SERVICES = "conflict_warning_services";
    static final String SUSPEND_SERVICE = "suspend_service";
    static final String SUSPEND_SERVICE_COMPONENT = "suspend_service_component";
    static final String SUSPEND_SERVICE_ACTIVE = "suspend_service_active";
    static final String SUSPEND_SERVICE_COMPONENTS = "suspend_service_components";
    static final String SUSPEND_SERVICE_IGNORED = "suspend_service_ignored";

    static final int AXIS_DPAD = 0;
    static final int AXIS_RIGHT_STICK = 1;
    static final int AXIS_LEFT_STICK = 2;

    static final int TARGET_BOTH = 0;
    static final int TARGET_TOP = 1;
    static final int TARGET_BOTTOM = 2;

    static final int THEME_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Flip brightness (ENABLED, default on) or volume (VOL_ENABLED, default off). */
    static boolean toggle(Context context, String key) {
        boolean fallback = ENABLED.equals(key);
        boolean on = !get(context).getBoolean(key, fallback);
        get(context).edit().putBoolean(key, on).apply();
        // Let both Quick Settings tiles redraw with the new state.
        for (Class<?> tile : new Class<?>[]{BrightnessTile.class, VolumeTile.class}) {
            android.service.quicksettings.TileService.requestListeningState(context,
                    new android.content.ComponentName(context, tile));
        }
        return on;
    }

    static int modifier(Context context) {
        return get(context).getInt(MODIFIER, KeyEvent.KEYCODE_BUTTON_R1);
    }

    /** Step (percent) for one press of a binding; Binding 2 starts from Binding 1's. */
    static int pressStep(SharedPreferences prefs, boolean second) {
        int first = prefs.getInt(PRESS_STEP, prefs.getInt(STEP, 5));
        return second ? prefs.getInt(B_PRESS_STEP, first) : first;
    }

    /** Step (percent) per repeat while held; follows the press step when linked. */
    static int holdStep(SharedPreferences prefs, boolean second) {
        if (prefs.getBoolean(LINK_HOLD_STEP, true)) return pressStep(prefs, second);
        int first = prefs.getInt(HOLD_STEP, pressStep(prefs, false));
        return second ? prefs.getInt(B_HOLD_STEP, first) : first;
    }

    /** Default key for any mapping preference, covering both bindings. */
    static int defaultKey(String preference) {
        if (MODIFIER.equals(preference)) return KeyEvent.KEYCODE_BUTTON_R1;
        if (B_MODIFIER.equals(preference)) return KeyEvent.KEYCODE_BUTTON_L1;
        if (UP_KEY.equals(preference) || B_UP_KEY.equals(preference)) return KeyEvent.KEYCODE_VOLUME_UP;
        if (DOWN_KEY.equals(preference) || B_DOWN_KEY.equals(preference)) return KeyEvent.KEYCODE_VOLUME_DOWN;
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    static boolean isBindingB(String preference) {
        return B_MODIFIER.equals(preference) || B_UP_KEY.equals(preference) || B_DOWN_KEY.equals(preference);
    }

    /**
     * The Thor's D-pad only reaches the service as an axis, never as DPAD_*
     * key presses, so a binding saved with DPAD buttons can never fire.
     * When D-pad/stick input is enabled, convert such buttons into the
     * equivalent recorded D-pad directions.
     */
    static void migrateDpadButtons(SharedPreferences prefs) {
        if (!prefs.getBoolean(ROOT_AXES, false)) return;
        SharedPreferences.Editor edit = prefs.edit();
        boolean changed = false;
        String[][] slots = {
                {UP_KEY, ROOT_BRIGHT_SOURCE, ROOT_BRIGHT_DIRECTION},
                {DOWN_KEY, ROOT_DIM_SOURCE, ROOT_DIM_DIRECTION},
                {B_UP_KEY, ROOT_B_BRIGHT_SOURCE, ROOT_B_BRIGHT_DIRECTION},
                {B_DOWN_KEY, ROOT_B_DIM_SOURCE, ROOT_B_DIM_DIRECTION}};
        for (String[] slot : slots) {
            int direction = dpadDirection(prefs.getInt(slot[0], defaultKey(slot[0])));
            if (direction == 0 || prefs.getInt(slot[2], 0) != 0) continue;
            edit.putInt(slot[1], AXIS_DPAD).putInt(slot[2], direction)
                    .putInt(slot[0], defaultKey(slot[0]));
            changed = true;
        }
        if (changed) edit.apply();
    }

    private static int dpadDirection(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return ROOT_DIRECTION_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return ROOT_DIRECTION_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return ROOT_DIRECTION_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ROOT_DIRECTION_RIGHT;
            default: return 0;
        }
    }

    /** Brighter/Dimmer of either binding: these can record D-pad/stick directions. */
    static boolean isDirectional(String preference) {
        return UP_KEY.equals(preference) || DOWN_KEY.equals(preference)
                || B_UP_KEY.equals(preference) || B_DOWN_KEY.equals(preference);
    }

    static boolean isModifier(String preference) {
        return MODIFIER.equals(preference) || B_MODIFIER.equals(preference);
    }
}
