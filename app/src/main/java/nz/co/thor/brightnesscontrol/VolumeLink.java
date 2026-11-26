/*
 * Volume-key modes adapted from Thor Volume Link
 * (https://github.com/pth2000/ThorVolumeLink)
 * Copyright (c) 2026 Thor Volume Link contributors. MIT License; see
 * THIRD_PARTY_NOTICES.md.
 */
package nz.co.thor.brightnesscontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes the hardware volume keys to the top screen, the bottom screen, both
 * (linked), or whichever screen has focus.
 *
 * <p>The top screen is Android's media stream. The bottom screen is AYN's
 * {@code secondary_screen_volume_level} (0-15), which a modern app may only
 * write through Shizuku or root.</p>
 */
final class VolumeLink {
    static final String SECONDARY_KEY = "secondary_screen_volume_level";
    static final int SECONDARY_MAX = 15;

    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final Pattern FOCUSED_DISPLAY =
            Pattern.compile("(?m)^\\s*FocusedDisplayId:\\s*(-?\\d+)\\s*$");

    private final Context context;
    private final Handler handler;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    // Key being held and the target chosen when it went down (fixed for the hold).
    private int heldKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean heldUp;
    private int heldMode = Prefs.VOL_MODE_PRIMARY;

    // Bottom-screen writes are slow (a Shizuku process each), so pending steps
    // are merged instead of queued.
    private boolean secondaryInFlight;
    private int pendingSecondaryDelta;
    private boolean linkedInFlight;
    private int pendingLinkedTarget = -1;
    private int lastMain = -1;
    private boolean warnedNoPrivilege;

    private long focusValue = FocusChangeSetting.MISSING_VALUE;
    private boolean focusCalibrating;
    private ContentObserver focusObserver;
    private boolean receiverRegistered;

    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (heldKey == KeyEvent.KEYCODE_UNKNOWN) return;
            step(heldMode, heldUp);
            handler.postDelayed(this, repeatDelay());
        }
    };

    /** Keeps the bottom screen in step when the top volume changes elsewhere. */
    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent == null || intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
            int previous = lastMain;
            lastMain = readMain();
            SharedPreferences prefs = Prefs.get(context);
            if (!prefs.getBoolean(Prefs.VOL_ENABLED, false)
                    || prefs.getInt(Prefs.VOL_MODE, Prefs.VOL_MODE_PRIMARY) != Prefs.VOL_MODE_LINKED) return;
            if (isInteractive() && !prefs.getBoolean(Prefs.VOL_AUTO_FOLLOW, true)) return;
            if (previous >= 0 && linkedIdle()) learnBalance(previous);
            requestLinkedSync();
        }
    };

    VolumeLink(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    void start() {
        lastMain = readMain();
        try {
            context.registerReceiver(volumeChanged, new IntentFilter(ACTION_VOLUME_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException ignored) { }
        focusObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { refreshFocus(); }
        };
        try {
            context.getContentResolver().registerContentObserver(
                    FocusChangeSetting.uri(), false, focusObserver);
        } catch (RuntimeException ignored) { }
        refreshFocus();
    }

    void stop() {
        cancelHold();
        if (receiverRegistered) {
            try { context.unregisterReceiver(volumeChanged); } catch (RuntimeException ignored) { }
            receiverRegistered = false;
        }
        if (focusObserver != null) {
            try { context.getContentResolver().unregisterContentObserver(focusObserver); }
            catch (RuntimeException ignored) { }
            focusObserver = null;
        }
        writer.shutdownNow();
    }

    /** True while this engine owns a held volume key (its release must come here). */
    boolean isHolding(int keyCode) {
        return heldKey != KeyEvent.KEYCODE_UNKNOWN && heldKey == keyCode;
    }

    /**
     * Handles a volume key when no brightness modifier is held. Returns true
     * when the key was consumed; false lets Android change the top volume.
     */
    boolean onVolumeKey(KeyEvent event, boolean screenOn) {
        int code = event.getKeyCode();
        if (isHolding(code)) {
            boolean consumed = heldMode != Prefs.VOL_MODE_PRIMARY;
            if (event.getAction() == KeyEvent.ACTION_UP) cancelHold();
            return consumed;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        SharedPreferences prefs = Prefs.get(context);
        int mode = prefs.getInt(Prefs.VOL_MODE, Prefs.VOL_MODE_PRIMARY);
        // Screen off: only Android can act reliably; Linked follows via the broadcast.
        if (!screenOn) mode = Prefs.VOL_MODE_PRIMARY;
        if (mode == Prefs.VOL_MODE_FOCUS) mode = focusedMode();
        cancelHold();
        heldKey = code;
        heldUp = code == KeyEvent.KEYCODE_VOLUME_UP;
        heldMode = mode;
        if (mode == Prefs.VOL_MODE_PRIMARY) return false;
        step(mode, heldUp);
        handler.postDelayed(repeat, repeatTimeout());
        return true;
    }

    private void step(int mode, boolean up) {
        int delta = (up ? 1 : -1) * Math.max(1, Prefs.get(context).getInt(Prefs.VOL_STEP, 1));
        if (mode == Prefs.VOL_MODE_LINKED) {
            if (linkedIdle()) learnBalance(readMain());
            lastMain = adjustMain(delta, Prefs.get(context).getBoolean(Prefs.VOL_SHOW_PANEL, true));
            requestLinkedSync();
        } else if (mode == Prefs.VOL_MODE_SECONDARY) {
            pendingSecondaryDelta += delta;
            drainSecondary();
        }
    }

    private void cancelHold() {
        handler.removeCallbacks(repeat);
        heldKey = KeyEvent.KEYCODE_UNKNOWN;
        heldMode = Prefs.VOL_MODE_PRIMARY;
    }

    // ---- Top screen (Android media stream) ----

    int readMain() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        return audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    int mainMax() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        return audio == null ? 15 : Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private int adjustMain(int delta, boolean showPanel) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return readMain();
        int target = Math.max(0, Math.min(mainMax(), readMain() + delta));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, showPanel ? AudioManager.FLAG_SHOW_UI : 0);
        return target;
    }

    // ---- Bottom screen (AYN setting, via Shizuku/root) ----

    static int readSecondary(Context context) {
        try {
            return clamp(Settings.System.getInt(context.getContentResolver(), SECONDARY_KEY));
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }

    static boolean privileged() {
        return ShizukuSupport.available() || RootAccessibilityController.shell("id -u") != null;
    }

    private void drainSecondary() {
        if (secondaryInFlight || pendingSecondaryDelta == 0) return;
        int delta = pendingSecondaryDelta;
        pendingSecondaryDelta = 0;
        int current = readSecondary(context);
        int target = clamp((current < 0 ? 0 : current) + delta);
        secondaryInFlight = true;
        writeSecondary(target, ok -> {
            secondaryInFlight = false;
            if (ok) toast("Bottom volume " + target + " / " + SECONDARY_MAX);
            drainSecondary();
        });
    }

    private boolean linkedIdle() {
        return !linkedInFlight && pendingLinkedTarget < 0;
    }

    private void requestLinkedSync() {
        pendingLinkedTarget = linkedTarget(readMain());
        drainLinked();
    }

    private void drainLinked() {
        if (linkedInFlight || pendingLinkedTarget < 0) return;
        int target = pendingLinkedTarget;
        pendingLinkedTarget = -1;
        linkedInFlight = true;
        writeSecondary(target, ok -> {
            linkedInFlight = false;
            drainLinked();
        });
    }

    /** Bottom level matching the top's percentage, plus any kept balance offset. */
    private int linkedTarget(int main) {
        if (main <= 0) return 0;
        int mapped = clamp(Math.round(main * (float) SECONDARY_MAX / mainMax()));
        SharedPreferences prefs = Prefs.get(context);
        if (!prefs.getBoolean(Prefs.VOL_KEEP_BALANCE, false)) return mapped;
        return clamp(mapped + prefs.getInt(Prefs.VOL_BALANCE, 0));
    }

    /** Keep balance: remember a bottom level the user changed by hand as an offset. */
    private void learnBalance(int main) {
        SharedPreferences prefs = Prefs.get(context);
        if (!prefs.getBoolean(Prefs.VOL_KEEP_BALANCE, false)) return;
        int current = readSecondary(context);
        if (current < 0 || current == linkedTarget(main)) return;
        int mapped = clamp(Math.round(main * (float) SECONDARY_MAX / mainMax()));
        prefs.edit().putInt(Prefs.VOL_BALANCE, current - mapped).apply();
    }

    interface Done { void done(boolean ok); }

    private void writeSecondary(int value, Done done) {
        writer.execute(() -> {
            boolean ok = privileged() && RootAccessibilityController.shell(
                    "settings put system " + SECONDARY_KEY + " " + clamp(value)) != null;
            handler.post(() -> {
                if (!ok && !warnedNoPrivilege) {
                    warnedNoPrivilege = true;
                    toast("Bottom-screen volume needs Shizuku or root");
                }
                if (ok) warnedNoPrivilege = false;
                done.done(ok);
            });
        });
    }

    // ---- Follow focus ----

    private void refreshFocus() {
        focusValue = FocusChangeSetting.read(context);
        FocusChangeSetting.observe(context, focusValue);
        if (!FocusChangeSetting.isCalibrated(context, focusValue)
                && Prefs.get(context).getInt(Prefs.VOL_MODE, 0) == Prefs.VOL_MODE_FOCUS) {
            calibrateFocus();
        }
    }

    private int focusedMode() {
        if (!FocusChangeSetting.isCalibrated(context, focusValue)) {
            calibrateFocus();
            return Prefs.VOL_MODE_PRIMARY;
        }
        return FocusChangeSetting.volumeMode(context, focusValue);
    }

    /** Pair the current focus_change reading with the focused display, once per boot. */
    private void calibrateFocus() {
        if (focusCalibrating || !FocusChangeSetting.isAvailable(focusValue)) return;
        focusCalibrating = true;
        long before = FocusChangeSetting.read(context);
        writer.execute(() -> {
            String dump = RootAccessibilityController.shellAll("dumpsys input");
            int display = -1;
            if (dump != null) {
                Matcher m = FOCUSED_DISPLAY.matcher(dump);
                if (m.find()) display = Integer.parseInt(m.group(1));
            }
            int found = display;
            handler.post(() -> {
                focusCalibrating = false;
                long after = FocusChangeSetting.read(context);
                focusValue = after;
                if (found >= 0 && before == after) {
                    FocusChangeSetting.calibrateFromDisplay(context, found,
                            FocusChangeSetting.ANCHOR_PRIVILEGED, after);
                }
            });
        });
    }

    // ---- helpers ----

    private boolean isInteractive() {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
    }

    private void toast(String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(SECONDARY_MAX, value));
    }

    private static long repeatTimeout() {
        return Math.max(1, ViewConfiguration.getKeyRepeatTimeout());
    }

    private static long repeatDelay() {
        return Math.max(1, ViewConfiguration.getKeyRepeatDelay());
    }
}
