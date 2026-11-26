/*
 * Adapted from Thor Volume Link (https://github.com/pth2000/ThorVolumeLink)
 * Copyright (c) 2026 Thor Volume Link contributors. MIT License; see
 * THIRD_PARTY_NOTICES.md.
 *
 * AYN's "focus_change" counter flips parity whenever key focus moves between
 * the two displays. Once one reading is paired with a known display, the
 * parity tells which screen currently has focus without any privilege.
 */
package nz.co.thor.brightnesscontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.view.Display;

final class FocusChangeSetting {
    static final String KEY = "focus_change";
    static final long MISSING_VALUE = -1L;

    private static final String STATE_FILE = "focus_change_state";
    private static final String STATE_BOOT_COUNT = "boot_count";
    private static final String STATE_MAPPING_VERSION = "mapping_version";
    private static final String STATE_PRIMARY_PARITY = "primary_parity";
    private static final String STATE_LAST_VALUE = "last_value";
    private static final String STATE_ANCHOR_VALUE = "anchor_value";
    private static final String STATE_ANCHOR_DISPLAY = "anchor_display";
    private static final String STATE_ANCHOR_SOURCE = "anchor_source";
    private static final int MAPPING_VERSION = 4;
    private static final long MISSING_BOOT_COUNT = Long.MIN_VALUE;
    private static final int MISSING_PARITY = -1;
    private static final int MISSING_DISPLAY = -1;

    static final int ANCHOR_NONE = 0;
    static final int ANCHOR_ACTIVITY = 1;
    static final int ANCHOR_ACCESSIBILITY = 2;
    static final int ANCHOR_PRIVILEGED = 3;

        static final class DebugState {
        final Long systemBootCount;
        final Long savedBootCount;
        final Long lastValue;
        final Integer primaryParity;
        final Long anchorValue;
        final Integer anchorDisplay;
        final int anchorSource;

        DebugState(
                Long systemBootCount,
                Long savedBootCount,
                Long lastValue,
                Integer primaryParity,
                Long anchorValue,
                Integer anchorDisplay,
                int anchorSource) {
            this.systemBootCount = systemBootCount;
            this.savedBootCount = savedBootCount;
            this.lastValue = lastValue;
            this.primaryParity = primaryParity;
            this.anchorValue = anchorValue;
            this.anchorDisplay = anchorDisplay;
            this.anchorSource = anchorSource;
        }
    }

    private FocusChangeSetting() {}

    static Uri uri() {
        return Settings.System.getUriFor(KEY);
    }

    static long read(Context context) {
        return Settings.System.getLong(
                context.getContentResolver(), KEY, MISSING_VALUE);
    }

    static boolean isAvailable(long value) {
        return value != MISSING_VALUE;
    }

        static synchronized void observe(Context context, long value) {
        if (context == null || !isAvailable(value)) return;
        SharedPreferences state = state(context);
        long bootCount = readBootCount(context);
        long savedBootCount = state.getLong(STATE_BOOT_COUNT, MISSING_BOOT_COUNT);
        int mappingVersion = state.getInt(STATE_MAPPING_VERSION, 0);
        if (savedBootCount == bootCount && mappingVersion == MAPPING_VERSION
                && state.getLong(STATE_LAST_VALUE, MISSING_VALUE) == value) {
            return;
        }
        SharedPreferences.Editor editor = state.edit()
                .putLong(STATE_BOOT_COUNT, bootCount)
                .putInt(STATE_MAPPING_VERSION, MAPPING_VERSION)
                .putLong(STATE_LAST_VALUE, value);
        if (savedBootCount != bootCount || mappingVersion != MAPPING_VERSION) {
            editor.remove(STATE_PRIMARY_PARITY)
                    .remove(STATE_ANCHOR_VALUE)
                    .remove(STATE_ANCHOR_DISPLAY)
                    .putInt(STATE_ANCHOR_SOURCE, ANCHOR_NONE);
        }
        editor.apply();
    }

        static synchronized void calibrateFromDisplay(Context context, int displayId) {
        calibrateFromDisplay(context, displayId, ANCHOR_ACTIVITY);
    }

    static synchronized void calibrateFromDisplay(
            Context context, int displayId, int anchorSource) {
        if (context == null || displayId < 0) return;
        long value = read(context);
        calibrateFromDisplay(context, displayId, anchorSource, value);
    }

        static synchronized boolean calibrateFromDisplay(
            Context context, int displayId, int anchorSource, long value) {
        if (context == null || displayId < 0 || !isAvailable(value)) return false;
        observe(context, value);

        int valueParity = parity(value);
        int primaryParity = displayId == Display.DEFAULT_DISPLAY
                ? valueParity : valueParity ^ 1;
        SharedPreferences values = state(context);
        int existingParity = values.getInt(STATE_PRIMARY_PARITY, MISSING_PARITY);
        int existingSource = values.getInt(STATE_ANCHOR_SOURCE, ANCHOR_NONE);
        int incomingPriority = anchorPriority(anchorSource);
        int existingPriority = anchorPriority(existingSource);

        if (isParity(existingParity)
                && (incomingPriority < existingPriority
                || (incomingPriority == existingPriority
                && existingParity != primaryParity))) {
            return false;
        }
        if (existingParity == primaryParity) {
            SharedPreferences.Editor confirmation = values.edit().putLong(STATE_LAST_VALUE, value);
            if (incomingPriority > existingPriority) {
                confirmation.putLong(STATE_ANCHOR_VALUE, value)
                        .putInt(STATE_ANCHOR_DISPLAY, displayId)
                        .putInt(STATE_ANCHOR_SOURCE, anchorSource);
            }
            confirmation.apply();
            return true;
        }
        values.edit()
                .putLong(STATE_BOOT_COUNT, readBootCount(context))
                .putInt(STATE_MAPPING_VERSION, MAPPING_VERSION)
                .putInt(STATE_PRIMARY_PARITY, primaryParity)
                .putLong(STATE_LAST_VALUE, value)
                .putLong(STATE_ANCHOR_VALUE, value)
                .putInt(STATE_ANCHOR_DISPLAY, displayId)
                .putInt(STATE_ANCHOR_SOURCE, anchorSource)
                .apply();
        return true;
    }

        static synchronized boolean needsCalibrationFrom(
            Context context, long value, int anchorSource) {
        if (!isAvailable(value)) return false;
        observe(context, value);
        SharedPreferences values = state(context);
        int existingParity = values.getInt(STATE_PRIMARY_PARITY, MISSING_PARITY);
        if (!isParity(existingParity)) return true;
        int existingSource = values.getInt(STATE_ANCHOR_SOURCE, ANCHOR_NONE);
        return anchorPriority(anchorSource) > anchorPriority(existingSource);
    }

    static synchronized boolean isCalibrated(Context context, long value) {
        if (!isAvailable(value)) return false;
        observe(context, value);
        return isParity(state(context).getInt(STATE_PRIMARY_PARITY, MISSING_PARITY));
    }

    static boolean targetsPrimary(Context context, long value) {
        if (!isAvailable(value)) return true;
        observe(context, value);
        int primaryParity = state(context).getInt(STATE_PRIMARY_PARITY, MISSING_PARITY);
        if (!isParity(primaryParity)) return true;
        return parity(value) == primaryParity;
    }

    static int volumeMode(Context context, long value) {
        if (!isAvailable(value)) return Prefs.VOL_MODE_PRIMARY;
        return targetsPrimary(context, value) ? Prefs.VOL_MODE_PRIMARY : Prefs.VOL_MODE_SECONDARY;
    }

    static synchronized DebugState debugState(Context context) {
        SharedPreferences values = state(context);
        long systemBootCount = readBootCount(context);
        long savedBootCount = values.getLong(STATE_BOOT_COUNT, MISSING_BOOT_COUNT);
        long lastValue = values.getLong(STATE_LAST_VALUE, MISSING_VALUE);
        int primaryParity = values.getInt(STATE_PRIMARY_PARITY, MISSING_PARITY);
        long anchorValue = values.getLong(STATE_ANCHOR_VALUE, MISSING_VALUE);
        int anchorDisplay = values.getInt(STATE_ANCHOR_DISPLAY, MISSING_DISPLAY);
        int anchorSource = values.getInt(STATE_ANCHOR_SOURCE, ANCHOR_NONE);
        return new DebugState(
                systemBootCount == MISSING_BOOT_COUNT ? null : Long.valueOf(systemBootCount),
                savedBootCount == MISSING_BOOT_COUNT ? null : Long.valueOf(savedBootCount),
                isAvailable(lastValue) ? Long.valueOf(lastValue) : null,
                isParity(primaryParity) ? Integer.valueOf(primaryParity) : null,
                isAvailable(anchorValue) ? Long.valueOf(anchorValue) : null,
                anchorDisplay < 0 ? null : Integer.valueOf(anchorDisplay),
                anchorSource);
    }

    private static int parity(long value) {
        return (int) (value & 1L);
    }

    private static boolean isParity(int value) {
        return value == 0 || value == 1;
    }

    private static int anchorPriority(int source) {
        if (source == ANCHOR_PRIVILEGED) return 3;
        if (source == ANCHOR_ACTIVITY) return 2;
        if (source == ANCHOR_ACCESSIBILITY) return 1;
        return 0;
    }

    private static long readBootCount(Context context) {
        try {
            return Settings.Global.getLong(
                    context.getContentResolver(), "boot_count", MISSING_BOOT_COUNT);
        } catch (Throwable ignored) {
            return MISSING_BOOT_COUNT;
        }
    }

    private static SharedPreferences state(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                STATE_FILE, Context.MODE_PRIVATE);
    }
}
