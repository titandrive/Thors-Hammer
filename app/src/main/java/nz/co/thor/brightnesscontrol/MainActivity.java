package nz.co.thor.brightnesscontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import rikka.shizuku.Shizuku;
import android.widget.Toast;

public class MainActivity extends Activity {
    private boolean suppressGentleWakeListener;
    private SharedPreferences prefs;
    private TextView permissionStatus;
    private TextView captureMessage;
    private TextView captureLabel;
    private TextView repeatLabel;
    private TextView rootStatus;
    private TextView modifierMappingLabel;
    private TextView brighterMappingLabel;
    private TextView dimmerMappingLabel;
    private LinearLayout bindingsContainer;
    private TextView setupHint;
    private LinearLayout setupPanel;
    private TextView statusPill;
    private LinearLayout statusRows;
    private View brightnessPage;
    private View volumePage;
    private LinearLayout volLinkedOptions;
    private LinearLayout volBody;
    private Runnable volPaint;

    /** Input controls; built once, shown inside the Settings popup. */
    private LinearLayout inputCard;
    private Button wakeSettingsButton;
    private AlertDialog setupDialog;
    private TextView bindingSummaryA;
    private TextView bindingSummaryB;
    private android.widget.ProgressBar topLevelBar;
    private android.widget.ProgressBar bottomLevelBar;
    private TextView topLevelText;
    private TextView bottomLevelText;
    private final Handler levelHandler = new Handler(Looper.getMainLooper());
    private final Runnable levelPoller = new Runnable() {
        @Override public void run() {
            refreshLevelBars();
            refreshVolumePage();
            levelHandler.postDelayed(this, 300);
        }
    };
    private TextView bModifierMappingLabel;
    private TextView bBrighterMappingLabel;
    private TextView bDimmerMappingLabel;
    private TextView safetyLabel;
    private Button brightnessPermissionButton;
    private Button keyDetectionButton;
    private TextView brightnessStatus;
    private TextView keyStatus;
    private Switch suspendServicesSwitch;
    private Button checkRootButton;
    private Button rootCheckPrimary;
    private boolean rootCheckRunning;
    private Switch enabledSwitch;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                if (Prefs.ENABLED.equals(key) && enabledSwitch != null) {
                    boolean enabled = sharedPreferences.getBoolean(Prefs.ENABLED, true);
                    if (enabledSwitch.isChecked() != enabled) {
                        enabledSwitch.setChecked(enabled);
                    }
                    runOnUiThread(this::applyBrightnessEnabled);
                }
                if (Prefs.ROOT_AXES.equals(key) || Prefs.GENTLE_WAKE.equals(key)
                        || Prefs.ENABLED.equals(key)
                        || Prefs.B_ENABLED.equals(key) || Prefs.VOL_ENABLED.equals(key)
                        || Prefs.VOL_MODE.equals(key)) {
                    runOnUiThread(this::refreshStatusCard);
                }
            };
    private boolean capturing;
    private boolean capturingRootDirection;
    private RootInputMonitor rootDirectionRecorder;
    private String capturePreference;
    private String captureTitle;
    private boolean captureAllowsVolume;
    private AlertDialog recordDialog;
    private TextView recordDialogMessage;
    private boolean recordDialogCompleted;
    private final Handler recordCountdownHandler = new Handler(Looper.getMainLooper());
    private Runnable recordCountdown;

    @Override
    protected void onCreate(Bundle state) {
        prefs = Prefs.get(this);
        prefs.edit().putInt(Prefs.THEME, Prefs.THEME_SYSTEM).apply();
        Palette.load(prefs);
        // A crashed or force-stopped recording must not leave the service in
        // capture mode on the next launch.
        prefs.edit().putBoolean(Prefs.CAPTURING, false).apply();
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == 1001 && checkRootButton != null) {
                // Android may publish the grant just after delivering the
                // callback, so refresh once immediately and once shortly
                // afterward to ensure the button reflects the final state.
                runOnUiThread(() -> {
                    refreshPrivilegeButton();
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                            && !selectedSuspendServices().isEmpty()) {
                        prefs.edit().putBoolean(Prefs.SUSPEND_SERVICE, true).apply();
                        if (suspendServicesSwitch != null) suspendServicesSwitch.setChecked(true);
                    }
                });
            }
        });
        hideNavigationBar();
        migrateUnsupportedMappings();
        buildUi();
        hideNavigationBar();
    }

    private void hideNavigationBar() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNavigationBar();
    }

    private void buildUi() {
        Prefs.migrateDpadButtons(prefs);
        // Migrate any older directional modifier mapping now that root axes
        // are brightness-only inputs.
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                && isDpadKey(prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1))) {
            prefs.edit().putInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1)
                    .remove(Prefs.ROOT_MODIFIER_SOURCE)
                    .remove(Prefs.ROOT_MODIFIER_DIRECTION).apply();
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.base);
        getWindow().setStatusBarColor(Palette.crust);
        getWindow().setNavigationBarColor(Palette.crust);
        // Let the cards use the available height down to the navigation bar.
        root.setPadding(dp(16), dp(2), dp(16), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Thor's Lightning \u26A1", 20, true);
        titleRow.addView(title);
        // Brightness | Volume tabs (the version lives in the Settings title).
        LinearLayout tabs = segmented(Prefs.UI_TAB, 0, new int[]{0, 1},
                new String[]{"Brightness", "Volume"}, 32, this::applyTab);
        titleRow.addView(tabs, new LinearLayout.LayoutParams(dp(200), dp(32)));
        ((LinearLayout.LayoutParams) tabs.getLayoutParams()).setMargins(dp(16), 0, 0, 0);
        addLevelBars(titleRow);
        heading.addView(titleRow);
        header.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));


        // Bare gear, same style as the info icons; ring only when focused.
        TextView settings = new TextView(this);
        settings.setText("\u2699\uFE0E");
        settings.setTextSize(26);
        settings.setTextColor(Palette.SUBTEXT0);
        settings.setGravity(Gravity.CENTER);
        settings.setFocusable(true);
        settings.setClickable(true);
        settings.setBackground(focusRing(CORNER));
        settings.setContentDescription("Settings");
        settings.setOnClickListener(v -> showSettingsDialog());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        settingsParams.setMargins(dp(4), 0, 0, 0);
        header.addView(settings, settingsParams);
        root.addView(header, matchMargins(0, 0, 0, 2));

        // [ Bindings or Volume keys (two-thirds) | Status + Reset (both tabs) ]
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        android.widget.FrameLayout pages = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams wide = weightedCardMargins(0, 0, 6, 0);
        wide.weight = 2f;
        columns.addView(pages, wide);

        LinearLayout setup = card();
        LinearLayout mappings = card();
        inputCard = setup; // shown in the Settings popup
        brightnessPage = scrollCard(mappings);
        pages.addView(brightnessPage, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        volumePage = buildVolumePage();
        pages.addView(volumePage, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout rightColumn = new LinearLayout(this);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.addView(scrollCard(statusCard()), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rightColumn.addView(resetCard(), matchMargins(0, 12, 0, 0));
        columns.addView(rightColumn, weightedCardMargins(6, 0, 0, 0));
        applyTab();

        // Permission controls live in the setup popup, not on the main screen.
        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        permissionStatus = text("", 14, true);
        permissionStatus.setVisibility(View.GONE);
        setupPanel.addView(permissionStatus, margins(0, 2, 0, 2));
        setupHint = text("Tap once to complete both required permissions.", 12, false);
        setupHint.setTextColor(Palette.SUBTEXT0);
        setupPanel.addView(setupHint, margins(0, 0, 0, 5));

        brightnessPermissionButton = button("Set up permissions");
        styleSetupButton(brightnessPermissionButton);
        brightnessPermissionButton.setGravity(Gravity.CENTER);
        brightnessPermissionButton.setTextSize(14);
        brightnessPermissionButton.setHeight(dp(36));
        brightnessPermissionButton.setOnClickListener(v -> {
            if (Settings.System.canWrite(this) && isAccessibilityServiceEnabled()) {
                AlertDialog complete = new AlertDialog.Builder(this)
                        .setCustomTitle(centeredDialogTitle("Everything is set!"))
                        .setMessage("Both permissions are enabled.\n\nYou can open either of the Android settings pages if you want to review or turn them off.")
                        .setNegativeButton("Accessibility settings", (dialog, which) -> openKeyDetection())
                        .setNeutralButton("Brightness settings", (dialog, which) -> openBrightnessPermission())
                        .setPositiveButton("Got it", null)
                        .create();
                complete.setOnShowListener(dialog -> {
                    View accessibility = complete.findViewById(android.R.id.button2);
                    if (accessibility != null) accessibility.setTranslationX(-dp(30));
                });
                complete.show();
                return;
            }
            if (Settings.System.canWrite(this) && !isAccessibilityServiceEnabled()) {
                showKeyTransitionPrompt();
                return;
            }
            if (!prefs.getBoolean(Prefs.BRIGHTNESS_GUIDE_SHOWN, false)) {
                new AlertDialog.Builder(this)
                        .setCustomTitle(centeredDialogTitle("Brightness permissions"))
                        .setMessage("Press \"CONTINUE\" to open Android settings.\n\nThen enable the \"Allow modifying system settings\" toggle for Thor's Lightning.\n\nPress the Thor's \"B\" button or the Android Back button to return here.")
                        .setPositiveButton("CONTINUE", (dialog, which) -> {
                            prefs.edit().putBoolean(Prefs.BRIGHTNESS_GUIDE_SHOWN, true).apply();
                            prefs.edit().putBoolean(Prefs.ADVANCE_TO_KEY_PENDING, true).apply();
                            openBrightnessPermission();
                        }).setNegativeButton("Cancel", null).show();
                return;
            }
            openBrightnessPermission();
        });
        setupPanel.addView(brightnessPermissionButton, matchMargins(0, 0, 0, 3));
        brightnessPermissionButton.getLayoutParams().height = dp(36);

        keyDetectionButton = button("2. Key detection");
        styleSetupButton(keyDetectionButton);
        keyDetectionButton.setOnClickListener(v -> {
            if (!prefs.getBoolean(Prefs.KEY_GUIDE_SHOWN, false)) {
                new AlertDialog.Builder(this)
                        .setCustomTitle(centeredDialogTitle("Key detection"))
                        .setMessage("Press \"Continue\" to open Android settings. Open \"Downloaded apps\", select \"Thor's Lightning controls\", toggle it on, then tap \"Allow\" and return here.")
                        .setPositiveButton("Continue", (dialog, which) -> {
                            prefs.edit().putBoolean(Prefs.KEY_GUIDE_SHOWN, true).apply();
                            prefs.edit().putBoolean(Prefs.ADVANCE_TO_KEY_PENDING, false)
                                    .putBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, true).apply();
                            openKeyDetection();
                        }).setNegativeButton("Cancel", null).show();
                return;
            }
            prefs.edit().putBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, true).apply();
            openKeyDetection();
        });
        setupPanel.addView(keyDetectionButton, matchMargins(0, 0, 0, 0));
        keyDetectionButton.setVisibility(View.GONE);
        brightnessStatus = text("", 13, false);
        keyStatus = text("", 13, false);
        brightnessStatus.setText("Brightness permissions needed");
        keyStatus.setText("Key detection needed");
        // Permission state is shown by the setup button; keep the card free
        // of duplicate status lines so Root options can use the space.

        addRootControls(setup);
        addReserveControls(setup);

        mappings.addView(headerWithInfo("Control brightness", "Control brightness",
                "Hold a binding's modifier, then press its Brighter or Dimmer to change "
                + "the screen it targets.\n\nTap a control (Modifier, Brighter or Dimmer) "
                + "and press the button or D-pad direction you want to assign.\n\n"
                + "Add a second binding to control the other screen with its own "
                + "modifier and step."), matchMargins(0, 0, 0, 6));
        // Brightness on/off (the Quick Settings tile and shortcut flip this too).
        LinearLayout bindingsHeader = (LinearLayout) mappings.getChildAt(mappings.getChildCount() - 1);
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Enable");
        enabledSwitch.setTextSize(14);
        enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(Prefs.ENABLED, checked).apply();
            applyBrightnessEnabled();
        });
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.setMargins(0, 0, dp(12), 0);
        bindingsHeader.addView(enabledSwitch, 1, switchLp);
        bindingsContainer = new LinearLayout(this);
        bindingsContainer.setOrientation(LinearLayout.VERTICAL);
        mappings.addView(bindingsContainer, matchMargins(0, 0, 0, 0));
        rebuildBindings();
        polish(root);
        View decor = getWindow().getDecorView();
        muteClicks(decor);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> muteClicks(decor));
        setContentView(root);
        root.post(() -> {
            if (!requiredPermissionsReady() || !prefs.getBoolean(Prefs.SETUP_GUIDE_SHOWN, false)) {
                showSetupDialog();
            } else {
                root.postDelayed(this::maybeShowConflictWarning, 350);
            }
        });
    }

    private void addRootControls(LinearLayout parent) {
        LinearLayout rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        parent.addView(rootBox, matchMargins(0, 0, 0, 0));
        rootBox.addView(headerWithInfo("Input", "Input",
                "Root or Shizuku is optional. Regular button mappings work without it.\n\n"
                + "Enable D-pad / Joystick: lets Brighter and Dimmer use D-pad or stick directions "
                + "(read through root or Shizuku). Games may still receive the same movement.\n\n"
                + "Suspend services during hold: the selected accessibility services are turned off "
                + "while a modifier is held and restored when it is released, so two apps don't react "
                + "to the same buttons. Known conflicts are pre-selected when root or Shizuku is enabled."),
                matchMargins(0, 0, 0, 0));
        rootStatus = text("", 12, false);
        rootStatus.setTextColor(Palette.SUBTEXT0);
        rootBox.addView(rootStatus, margins(0, 0, 0, 1));
        Button rootCheckFirst = button("Check privilege access");
        rootCheckPrimary = rootCheckFirst;
        styleSetupButton(rootCheckFirst);
        rootCheckFirst.setTextSize(14);
        rootCheckFirst.setHeight(dp(36));
        rootCheckFirst.setPadding(dp(6), 0, dp(6), 0);
        rootCheckFirst.setGravity(Gravity.CENTER);
        rootCheckFirst.setBackgroundTintList(ColorStateList.valueOf(Palette.surface1));
        rootCheckFirst.setOnClickListener(v -> checkRootAccess(rootCheckFirst));
        rootBox.addView(rootCheckFirst, matchMargins(0, 1, 0, -4));
        rootCheckFirst.getLayoutParams().height = dp(36);
        Switch rootAxes = new Switch(this);
        rootAxes.setText("Enable D-pad / Joystick");
        rootAxes.setTextSize(14);
        rootAxes.setChecked(prefs.getBoolean(Prefs.ROOT_AXES, false));
        rootAxes.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootBox.addView(rootAxes, matchMargins(0, 12, 0, 4));
        updateRootCheckVisual(rootCheckFirst);


        checkRootButton = button("Check privilege access");
        styleSetupButton(checkRootButton);
        checkRootButton.setTextSize(14);
        checkRootButton.setHeight(dp(36));
        checkRootButton.setPadding(dp(6), 0, dp(6), 0);
        checkRootButton.setGravity(Gravity.CENTER);
        Button checkRoot = checkRootButton;
        checkRoot.setOnClickListener(v -> checkRootAccess(checkRoot));
        rootBox.addView(checkRoot, matchMargins(0, 1, 0, -4));
        checkRoot.getLayoutParams().height = dp(36);
        checkRoot.setVisibility(View.GONE);

        Switch suspend = new Switch(this);
        suspendServicesSwitch = suspend;
        suspend.setText("Suspend services during hold");
        suspend.setTextSize(14);
        suspend.setChecked(prefs.getBoolean(Prefs.SUSPEND_SERVICE, false));
        suspend.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootBox.addView(suspend, matchMargins(0, 2, 0, 0));
        suspend.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(Prefs.SUSPEND_SERVICE, checked).apply();
            updateRootCheckVisual(rootCheckFirst);
            if (checked) autoConfigureVolumeLinkSuspension();
        });

        Button chooseService = button("Choose services");
        styleSetupButton(chooseService);
        chooseService.setHeight(dp(36));
        chooseService.setGravity(Gravity.CENTER);
        chooseService.setBackgroundTintList(ColorStateList.valueOf(Palette.surface1));
        rootBox.addView(chooseService, matchMargins(0, 0, 0, 0));
        chooseService.getLayoutParams().height = dp(36);
        chooseService.setTranslationY(0);
        chooseService.setOnClickListener(v -> {
            if (!hasRootAccess() && !ShizukuSupport.available()) {
                Toast.makeText(this, "Root/Shizuku access is needed to choose services.", Toast.LENGTH_LONG).show();
                return;
            }
            chooseSuspendedService();
        });
        suspend.setOnCheckedChangeListener((button, checked) -> {
            if (checked && ((!hasRootAccess() && !ShizukuSupport.available()) || selectedSuspendServices().isEmpty())) {
                button.setChecked(false);
                Toast.makeText(this, "Enable Root/Shizuku and choose a service first.", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean(Prefs.SUSPEND_SERVICE, checked).apply();
        });

        rootAxes.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !hasRootAccess() && !ShizukuSupport.available()) {
                button.setChecked(false);
                Toast.makeText(this, "Root/Shizuku access is needed for D-pad or joystick input.", Toast.LENGTH_LONG).show();
                updateRootStatus(false);
                return;
            }
            prefs.edit().putBoolean(Prefs.ROOT_AXES, checked).apply();
            if (checked) {
                // Turn any D-pad keys saved while this was off into directions.
                Prefs.migrateDpadButtons(prefs);
                updateRootMappingLabels();
                updateBindingSummaries();
                // Root axes are brightness inputs only; do not allow a stick or
                // D-pad direction to remain as the modifier.
                prefs.edit().remove(Prefs.ROOT_MODIFIER_SOURCE)
                        .remove(Prefs.ROOT_MODIFIER_DIRECTION).apply();
                int modifierKey = prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1);
                if (isDpadKey(modifierKey)) {
                    prefs.edit().putInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1).apply();
                }
            }
            if (checked) autoConfigureVolumeLinkSuspension();
            updateRootStatus(checked);
            updateRootMappingLabels();
            updateSafetyText();
            if (checked) {
                new AlertDialog.Builder(this)
                        .setTitle("Privileged input limitations")
                        .setMessage("Root or Shizuku access is required to read D-pad and stick directions. Please be aware:\n\n"
                                + "• These controls are available for Brighter and Dimmer mappings only; they cannot be used as the modifier.\n"
                                + "• Privileged input reading does not guarantee that games will stop receiving the same movement. A game may react at the same time as the brightness change.\n"
                                + "• Any D-pad or stick direction can be recorded for Brighter and Dimmer in either binding.\n"
                                + "• The Thor L2/R2 triggers can report both button presses and analogue axes, which may cause unexpected results depending on the mapping.\n\n"
                                + "Disable this option if it causes conflicts or unwanted input.")
                        .setPositiveButton("Got it", (dialog, which) ->
                                prefs.edit().putBoolean(Prefs.ROOT_LIMIT_ACK, true).apply())
                        .setNegativeButton("Cancel", (dialog, which) ->
                                rootAxes.setChecked(false))
                        .setOnCancelListener(dialog -> rootAxes.setChecked(false))
                        .show();
            }
        });
        updateRootStatus(rootAxes.isChecked());
    }

    private void checkRootAccess(Button button) {
        button.setEnabled(false);
        new Thread(() -> {
            boolean shizuku = ShizukuSupport.available();
            boolean shizukuNeedsPermission = ShizukuSupport.runningWithoutPermission();
            boolean available = !shizuku && hasRootAccess();
            runOnUiThread(() -> {
                button.setEnabled(true);
                if (shizukuNeedsPermission) {
                    button.setText("Shizuku permission needed");
                    applyStatus(button, false);
                    ShizukuSupport.requestPermission();
                    new Handler(Looper.getMainLooper()).postDelayed(this::refreshPrivilegeButton, 500);
                    new Handler(Looper.getMainLooper()).postDelayed(this::refreshPrivilegeButton, 1500);
                    updateRootStatus(false);
                    return;
                }
                button.setText(shizuku ? "Shizuku (ADB) - Enabled"
                        : available ? "Root access - Enabled"
                        : "Privilege access - Needed");
                applyStatus(button, available || shizuku);
                if (available || shizuku) autoConfigureVolumeLinkSuspension();
                // Keep the detected privilege text visible after every check,
                // including repeated taps on the button.
                updateRootStatus(available || shizuku);
            });
        }, "ThorRootCheck").start();
    }

    private void refreshPrivilegeButton() {
        new Thread(() -> {
            boolean shizuku = ShizukuSupport.available();
            boolean root = !shizuku && hasRootAccess();
            runOnUiThread(() -> {
                checkRootButton.setText(shizuku ? "Shizuku (ADB) - Enabled"
                        : root ? "Root access - Enabled" : "Privilege access - Needed");
                applyStatus(checkRootButton, root || shizuku);
                updateRootStatus(root || shizuku);
            });
        }, "ThorPrivilegeRefresh").start();
    }

    private void openBrightnessPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openKeyDetection() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void showKeyTransitionPrompt() {
        new AlertDialog.Builder(this).setCustomTitle(centeredDialogTitle("Key detection"))
                .setMessage("Press \"CONTINUE\" to open Android Accessibility settings.\n\nThen:\n- Scroll to \"Downloaded apps\".\n- Select \"Thor's Lightning controls\".\n- Toggle it on.\n- Tap \"Allow\" when Android asks.\n\nPress the Thor's \"B\" button or the Android Back button once to return here.")
                .setPositiveButton("CONTINUE", (d, w) -> openKeyDetection())
                .setNegativeButton("Later", null).show();
    }

    private void showBrightnessTransitionPrompt() {
        new AlertDialog.Builder(this).setCustomTitle(centeredDialogTitle("Brightness permissions"))
                .setMessage("After pressing \"Continue\":\n\n- Then enable the \"Allow modifying system settings\" toggle for Thor's Lightning.\n- Press the Thor's \"B\" button or the Android Back button to return here.")
                .setPositiveButton("Continue", (d, w) -> openBrightnessPermission())
                .setNegativeButton("Later", null).show();
    }

    private void chooseSuspendedService() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        java.util.ArrayList<AccessibilityServiceInfo> services = new java.util.ArrayList<>();
        if (manager != null) {
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                String id = info.getId();
                if (!id.startsWith(getPackageName() + "/")) services.add(info);
            }
            // Some Android builds return an empty list for FEEDBACK_ALL_MASK even
            // though services are enabled. Fall back to the secure enabled list
            // and installed service metadata so the picker remains usable.
            if (services.isEmpty()) {
                String enabled = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                java.util.Set<String> enabledIds = new java.util.HashSet<>();
                if (enabled != null) enabledIds.addAll(java.util.Arrays.asList(enabled.split(":")));
                for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
                    String id = info.getId();
                    if (!id.startsWith(getPackageName() + "/") && enabledIds.contains(id)) services.add(info);
                }
                // A few firmware builds expose enabled IDs with a different
                // component spelling. The installed list is still authoritative;
                // use it as a final fallback and exclude this app itself.
                if (services.isEmpty()) {
                    for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
                        if (!info.getId().startsWith(getPackageName() + "/")) services.add(info);
                    }
                }
            }
        }
        if (services.isEmpty()) {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            if (enabled != null) for (String id : enabled.split(":"))
                if (!id.isEmpty() && !id.startsWith(getPackageName() + "/")) ids.add(id);
            if (ids.isEmpty()) {
                Toast.makeText(this, "No other enabled accessibility services found", Toast.LENGTH_LONG).show();
                return;
            }
            java.util.Set<String> selected = selectedSuspendServices();
            String[] labels = ids.toArray(new String[0]);
            boolean[] checked = new boolean[labels.length];
            for (int i = 0; i < labels.length; i++) checked[i] = selected.contains(labels[i]);
            new AlertDialog.Builder(this).setTitle("Choose services to suspend (" + selected.size() + " selected)")
                    .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                        java.util.Set<String> ignored = new java.util.HashSet<>(prefs.getStringSet(Prefs.SUSPEND_SERVICE_IGNORED, java.util.Collections.emptySet()));
                        if (isChecked) { selected.add(ids.get(which)); ignored.remove(ids.get(which)); }
                        else { selected.remove(ids.get(which)); ignored.add(ids.get(which)); }
                        prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_IGNORED, ignored).apply();
                    })
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (dialog, which) -> prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_COMPONENTS, selected).apply())
                    .show();
            return;
        }
        String[] labels = new String[services.size()];
        for (int i = 0; i < services.size(); i++) {
            CharSequence label = services.get(i).getResolveInfo().loadLabel(getPackageManager());
            labels[i] = label + "\n" + services.get(i).getId();
        }
        java.util.Set<String> selected = selectedSuspendServices();
        boolean[] checked = new boolean[services.size()];
        for (int i = 0; i < services.size(); i++) checked[i] = selected.contains(services.get(i).getId());
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Choose services to suspend")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    java.util.Set<String> ignored = new java.util.HashSet<>(prefs.getStringSet(Prefs.SUSPEND_SERVICE_IGNORED, java.util.Collections.emptySet()));
                    if (isChecked) { selected.add(services.get(which).getId()); ignored.remove(services.get(which).getId()); }
                    else { selected.remove(services.get(which).getId()); ignored.add(services.get(which).getId()); }
                    prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_IGNORED, ignored).apply();
                    ((AlertDialog) dialog).setTitle("Choose services to suspend (" + selected.size() + " selected)");
                }).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> prefs.edit()
                        .putStringSet(Prefs.SUSPEND_SERVICE_COMPONENTS, selected)
                        .putString(Prefs.SUSPEND_SERVICE_COMPONENT, selected.isEmpty() ? "" : selected.iterator().next())
                        .apply());
        AlertDialog picker = builder.create();
        picker.setOnShowListener(dialog ->
                picker.setTitle("Choose services to suspend (" + selected.size() + " selected)"));
        picker.show();
    }

    private java.util.Set<String> selectedSuspendServices() {
        java.util.Set<String> selected = new java.util.HashSet<>(prefs.getStringSet(
                Prefs.SUSPEND_SERVICE_COMPONENTS, java.util.Collections.emptySet()));
        if (selected.isEmpty()) {
            String legacy = prefs.getString(Prefs.SUSPEND_SERVICE_COMPONENT, "");
            if (legacy.length() > 0) selected.add(legacy);
        }
        return selected;
    }

    /** Rebuilds the binding boxes (Binding 1, then Binding 2 or an Add button). */
    private void rebuildBindings() {
        if (bindingsContainer == null) return;
        bindingsContainer.removeAllViews();
        modifierMappingLabel = brighterMappingLabel = dimmerMappingLabel = null;
        bModifierMappingLabel = bBrighterMappingLabel = bDimmerMappingLabel = null;
        LinearLayout.LayoutParams left = matchMargins(0, 0, 0, 8);
        LinearLayout.LayoutParams right = matchMargins(0, 0, 0, 8);
        bindingsContainer.addView(bindingBox(false), left);
        bindingsContainer.addView(bindingBox(true), right);
        polish(bindingsContainer);
        applyBrightnessEnabled();
    }

    /** This binding's step per press, plus step per repeat when not linked. */
    private void addStepControls(LinearLayout box, boolean second) {
        String pressKey = second ? Prefs.B_PRESS_STEP : Prefs.PRESS_STEP;
        String holdKey = second ? Prefs.B_HOLD_STEP : Prefs.HOLD_STEP;
        boolean linked = prefs.getBoolean(Prefs.LINK_HOLD_STEP, true);

        TextView pressValue = valueText();
        box.addView(valueRow("Step per press", pressValue), matchMargins(0, 2, 0, 0));
        SeekBar press = new SeekBar(this);
        press.setMax(24);
        int savedPress = Prefs.pressStep(prefs, second);
        press.setProgress(savedPress - 1);
        pressValue.setText(savedPress + "%");
        press.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int percent = value + 1;
                pressValue.setText(percent + "%");
                if (!fromUser) return;
                SharedPreferences.Editor edit = prefs.edit().putInt(pressKey, percent);
                if (!second) edit.putInt(Prefs.STEP, percent);
                edit.apply();
            }
        });
        box.addView(press, matchMargins(0, 0, 0, 0));
        if (linked) return;

        TextView holdValue = valueText();
        box.addView(valueRow("Step per repeat", holdValue), matchMargins(0, 2, 0, 0));
        SeekBar hold = new SeekBar(this);
        hold.setMax(24);
        int savedHold = Prefs.holdStep(prefs, second);
        hold.setProgress(savedHold - 1);
        holdValue.setText(savedHold + "%");
        hold.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int percent = value + 1;
                holdValue.setText(percent + "%");
                if (fromUser) prefs.edit().putInt(holdKey, percent).apply();
            }
        });
        box.addView(hold, matchMargins(0, 0, 0, 0));
    }

    private void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        // Only interactive views take controller focus; plain labels never do.
        if (view.isClickable() || view instanceof SeekBar) view.setFocusable(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledDeep(group.getChildAt(i), enabled);
        }
    }

    /** One self-contained binding: modifier, Brighter, Dimmer, steps and its screen. */
    private LinearLayout bindingBox(boolean second) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(4), dp(10), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Palette.mantle);
        background.setCornerRadius(dp(CORNER));
        background.setStroke(dp(1), Palette.surface1);
        box.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(26));
        TextView title = text(second ? "Binding 2" : "Binding 1", 16, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean bOn = !second || prefs.getBoolean(Prefs.B_ENABLED, false);
        if (second) {
            // Binding 2 is always shown; switching it off dims it instead.
            Switch on = new Switch(this);
            on.setText(bOn ? "On" : "Off");
            on.setTextSize(13);
            on.setChecked(bOn);
            on.setOnCheckedChangeListener((button, checked) -> {
                prefs.edit().putBoolean(Prefs.B_ENABLED, checked).apply();
                if (checked) ensureDistinctModifiers();
                rebuildBindings();
            });
            header.addView(on);
        }
        box.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        String modifierPref = second ? Prefs.B_MODIFIER : Prefs.MODIFIER;
        String upPref = second ? Prefs.B_UP_KEY : Prefs.UP_KEY;
        String downPref = second ? Prefs.B_DOWN_KEY : Prefs.DOWN_KEY;
        // Modifier / Brighter / Dimmer as three tappable chips; tap one to record it.
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        addMappingRow(chips, "Modifier", modifierPref,
                prefs.getInt(modifierPref, Prefs.defaultKey(modifierPref)), false);
        addMappingRow(chips, "Brighter", upPref,
                prefs.getInt(upPref, Prefs.defaultKey(upPref)), true);
        addMappingRow(chips, "Dimmer", downPref,
                prefs.getInt(downPref, Prefs.defaultKey(downPref)), true);
        box.addView(chips, matchMargins(0, 4, 0, 2));

        addStepControls(box, second);

        box.addView(second
                ? targetSelector(Prefs.B_TARGET, Prefs.TARGET_BOTTOM)
                : targetSelector(Prefs.TARGET, Prefs.TARGET_BOTH), matchMargins(0, 4, 0, 0));
        if (!bOn) {
            // Dim and lock everything below the header while switched off.
            for (int i = 1; i < box.getChildCount(); i++) {
                View child = box.getChildAt(i);
                child.setAlpha(0.4f);
                setEnabledDeep(child, false);
            }
        }
        updateBindingSummaries();
        return box;
    }

    /** Binding 2 must not reuse Binding 1's modifier. */
    private void ensureDistinctModifiers() {
        int first = prefs.getInt(Prefs.MODIFIER, Prefs.defaultKey(Prefs.MODIFIER));
        int second = prefs.getInt(Prefs.B_MODIFIER, Prefs.defaultKey(Prefs.B_MODIFIER));
        if (first == second) {
            prefs.edit().putInt(Prefs.B_MODIFIER, first == KeyEvent.KEYCODE_BUTTON_L1
                    ? KeyEvent.KEYCODE_BUTTON_R1 : KeyEvent.KEYCODE_BUTTON_L1).apply();
        }
    }

    private String modifierNames() {
        String names = keyName(prefs.getInt(Prefs.MODIFIER, Prefs.defaultKey(Prefs.MODIFIER)));
        if (prefs.getBoolean(Prefs.B_ENABLED, false)) {
            names += " and " + keyName(prefs.getInt(Prefs.B_MODIFIER, Prefs.defaultKey(Prefs.B_MODIFIER)));
        }
        return names;
    }

    /**
     * Top / Bottom / Both as a compact segmented control bound to one target
     * preference. Single-line pills avoid the wrapping radio labels.
     */
    private LinearLayout targetSelector(String preference, int fallback) {
        return segmented(preference, fallback,
                new int[]{Prefs.TARGET_TOP, Prefs.TARGET_BOTTOM, Prefs.TARGET_BOTH},
                new String[]{"Top", "Bottom", "Both"}, 32, this::updateBindingSummaries);
    }

    /** Row of pills bound to an int preference; the selected pill is filled. */
    private LinearLayout segmented(String preference, int fallback, int[] values,
                                   String[] names, int heightDp, Runnable onChange) {
        LinearLayout segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] pills = new TextView[values.length];
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            int selected = prefs.getInt(preference, fallback);
            for (int i = 0; i < pills.length; i++) {
                boolean on = values[i] == selected;
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(dp(CORNER));
                shape.setColor(on ? Palette.MAUVE : Palette.surface0);
                shape.setStroke(dp(1), on ? Palette.MAUVE : Palette.surface1);
                pills[i].setBackground(shape);
                pills[i].setTextColor(on ? Palette.onAccent() : Palette.TEXT);
            }
        };
        for (int i = 0; i < values.length; i++) {
            TextView pill = text(names[i], 13, true);
            pill.setSingleLine(true);
            pill.setGravity(Gravity.CENTER);
            pill.setFocusable(true);
            pill.setClickable(true);
            final int value = values[i];
            pill.setOnClickListener(v -> {
                prefs.edit().putInt(preference, value).apply();
                refresh[0].run();
                if (onChange != null) onChange.run();
            });
            pills[i] = pill;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
            params.setMargins(i == 0 ? 0 : dp(3), 0, i == values.length - 1 ? 0 : dp(3), 0);
            segments.addView(pill, params);
        }
        refresh[0].run();
        return segments;
    }

    /** Dim and lock the volume controls while volume control is switched off. */
    private void applyVolumeEnabled() {
        if (volBody == null) return;
        boolean on = prefs.getBoolean(Prefs.VOL_ENABLED, false);
        volBody.setAlpha(on ? 1f : 0.4f);
        setEnabledDeep(volBody, on);
        if (on && volPaint != null) volPaint.run(); // re-applies the Linked options state
    }

    /** Dim the bindings while brightness control is switched off. */
    private void applyBrightnessEnabled() {
        if (bindingsContainer == null) return;
        bindingsContainer.setAlpha(prefs.getBoolean(Prefs.ENABLED, true) ? 1f : 0.4f);
    }

    private void applyTab() {
        if (brightnessPage != null) haptic(getWindow().getDecorView());
        boolean volume = prefs.getInt(Prefs.UI_TAB, 0) == 1;
        refreshLevelBars();
        if (brightnessPage != null) brightnessPage.setVisibility(volume ? View.GONE : View.VISIBLE);
        if (volumePage != null) volumePage.setVisibility(volume ? View.VISIBLE : View.GONE);
        refreshVolumePage();
    }

    // ---------------- Volume tab ----------------

    private static final String[][] VOL_MODES = {
            {"Top screen", "Android\u2019s normal volume"},
            {"Bottom screen", "Only the bottom screen"},
            {"Linked", "Both screens move together"},
            {"Follow focus", "The screen you last used"}};

    private ScrollView buildVolumePage() {
        // Left (two columns wide): what the volume keys do.
        LinearLayout keys = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(section("Control volume"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch handle = new Switch(this);
        handle.setText("Enable");
        handle.setTextSize(14);
        handle.setChecked(prefs.getBoolean(Prefs.VOL_ENABLED, false));
        handle.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.VOL_ENABLED, checked).apply();
            applyVolumeEnabled();
            refreshVolumePage();
        });
        head.addView(handle);
        keys.addView(head, matchMargins(0, 4, 0, 2));
        // Everything below the header; dimmed and locked while volume is off.
        volBody = new LinearLayout(this);
        volBody.setOrientation(LinearLayout.VERTICAL);
        keys.addView(volBody, matchMargins(0, 0, 0, 0));
        TextView hint = text("Volume keys pressed without a brightness modifier. "
                + "Modifier + volume still changes brightness.", 12, false);
        hint.setTextColor(Palette.SUBTEXT0);
        volBody.addView(hint, matchMargins(0, 0, 0, 10));

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] tileViews = new TextView[VOL_MODES.length];
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            int mode = prefs.getInt(Prefs.VOL_MODE, Prefs.VOL_MODE_PRIMARY);
            for (int i = 0; i < tileViews.length; i++) {
                boolean on = i == mode;
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(CORNER));
                bg.setColor(on ? Palette.withAlpha(Palette.MAUVE, 0x33) : Palette.surface0);
                bg.setStroke(dp(on ? 2 : 1), on ? Palette.MAUVE : Palette.surface1);
                tileViews[i].setBackground(bg);
            }
            if (volLinkedOptions != null) {
                boolean linked = mode == Prefs.VOL_MODE_LINKED;
                volLinkedOptions.setAlpha(linked ? 1f : 0.4f);
                setEnabledDeep(volLinkedOptions, linked);
            }
        };
        for (int i = 0; i < VOL_MODES.length; i++) {
            TextView tile = text("", 15, true);
            tile.setText(styledTile(VOL_MODES[i][0], VOL_MODES[i][1]));
            tile.setPadding(dp(12), dp(8), dp(10), dp(8));
            tile.setMinHeight(dp(64));
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setFocusable(true);
            tile.setClickable(true);
            final int mode = i;
            tile.setOnClickListener(v -> {
                prefs.edit().putInt(Prefs.VOL_MODE, mode).apply();
                volPaint = paint[0];
        paint[0].run();
            });
            tileViews[i] = tile;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == VOL_MODES.length - 1 ? 0 : dp(4), 0);
            tiles.addView(tile, lp);
        }
        volBody.addView(tiles, matchMargins(0, 0, 0, 10));

        TextView stepValue = valueText();
        volBody.addView(valueRow("Step per press", stepValue), matchMargins(0, 2, 0, 0));
        SeekBar step = new SeekBar(this);
        step.setMax(9);
        step.setTickMark(tickMark());
        int savedStep = Math.max(1, Math.min(10, prefs.getInt(Prefs.VOL_STEP, 1)));
        step.setProgress(savedStep - 1);
        stepValue.setText(String.valueOf(savedStep));
        step.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                stepValue.setText(String.valueOf(value + 1));
                if (fromUser) prefs.edit().putInt(Prefs.VOL_STEP, value + 1).apply();
            }
        });
        volBody.addView(step, matchMargins(0, 0, 0, 4));

        volBody.addView(text("Linked", 14, true), matchMargins(0, 2, 0, 2));
        volLinkedOptions = new LinearLayout(this);
        volLinkedOptions.setOrientation(LinearLayout.VERTICAL);
        volLinkedOptions.addView(prefSwitch("Keep volume balance (remember a bottom level you set by hand)",
                Prefs.VOL_KEEP_BALANCE, false), matchMargins(0, 0, 0, 0));
        volLinkedOptions.addView(prefSwitch("Follow volume changes from other apps",
                Prefs.VOL_AUTO_FOLLOW, true), matchMargins(0, 0, 0, 0));
        volLinkedOptions.addView(prefSwitch("Show Android\u2019s volume panel",
                Prefs.VOL_SHOW_PANEL, true), matchMargins(0, 0, 0, 0));
        volBody.addView(volLinkedOptions, matchMargins(0, 0, 0, 0));
        paint[0].run();

        ScrollView page = scrollCard(keys);
        polish(page);
        applyVolumeEnabled();
        return page;
    }

    /** Small dot drawn at each step of a discrete slider. */
    private android.graphics.drawable.Drawable tickMark() {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Palette.OVERLAY1);
        dot.setSize(dp(4), dp(4));
        return dot;
    }

    private CharSequence styledTile(String title, String detail) {
        String full = title + "\n" + detail;
        SpannableString styled = new SpannableString(full);
        styled.setSpan(new ForegroundColorSpan(Palette.SUBTEXT0), title.length(), full.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.RelativeSizeSpan(0.8f), title.length(), full.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private Switch prefSwitch(String label, String key, boolean fallback) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(13);
        toggle.setChecked(prefs.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        return toggle;
    }

    private void refreshVolumePage() {
        refreshStatusCard();
    }

    private boolean volumeLinkConflict() {
        if (!prefs.getBoolean(Prefs.VOL_ENABLED, false)) return false;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains("io.github.thorvolume.control");
    }


    /** Brightness (repeat behaviour) and Sleep/Wake, opened from the header gear. */
    private void showSettingsDialog() {
        // Left: Brightness + Sleep/Wake. Right: Input (persistent views).
        LinearLayout behaviour = card();
        addBehaviourControls(behaviour);
        if (inputCard.getParent() != null) ((ViewGroup) inputCard.getParent()).removeView(inputCard);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(20), 0, dp(20), dp(4));
        // Two matching cards, equal height, so headings and switches line up.
        content.setBaselineAligned(false);
        LinearLayout.LayoutParams leftCol = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftCol.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams rightCol = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightCol.setMargins(dp(8), 0, 0, 0);
        content.addView(behaviour, leftCol);
        content.addView(inputCard, rightCol);
        polish(content);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Settings  \u00B7  v"
                        + appVersionName().replaceFirst("^v", "")))
                .setView(scroll)
                .setPositiveButton("Done", null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (inputCard.getParent() != null) ((ViewGroup) inputCard.getParent()).removeView(inputCard);
        });
        dialog.setOnShowListener(d -> dialog.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addBehaviourControls(LinearLayout behaviour) {

        behaviour.addView(headerWithInfo("Brightness", "Brightness",
                "Same step while holding: holding Brighter or Dimmer repeats each binding's "
                + "step per press. Turn it off to give each binding a separate step per "
                + "repeat.\n\nRepeat speed: how often the step repeats while held."),
                matchMargins(0, 0, 0, 0));
        // Step sizes live in each binding box; these two apply to both.
        Switch linkHold = new Switch(this);
        linkHold.setText("Same step while holding");
        linkHold.setTextSize(13);
        linkHold.setChecked(prefs.getBoolean(Prefs.LINK_HOLD_STEP, true));
        linkHold.setOnCheckedChangeListener((button, checked) -> {
            SharedPreferences.Editor edit = prefs.edit().putBoolean(Prefs.LINK_HOLD_STEP, checked);
            if (!checked) {
                // Unlinking starts each binding's repeat step at its press step.
                edit.putInt(Prefs.HOLD_STEP, Prefs.pressStep(prefs, false));
                if (prefs.getBoolean(Prefs.B_ENABLED, false)) {
                    edit.putInt(Prefs.B_HOLD_STEP, Prefs.pressStep(prefs, true));
                }
            }
            edit.apply();
            rebuildBindings();
        });
        behaviour.addView(linkHold, matchMargins(0, 0, 0, 0));

        repeatLabel = valueText();
        behaviour.addView(valueRow("Repeat speed", repeatLabel), matchMargins(0, 2, 0, 0));
        SeekBar repeat = new SeekBar(this);
        repeat.setMax(18);
        int savedDelay = prefs.getInt(Prefs.REPEAT_DELAY, 150);
        repeat.setProgress(Math.max(0, Math.min(18, (savedDelay - 100) / 50)));
        updateRepeatLabel(100 + repeat.getProgress() * 50);
        repeat.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int delay = 100 + value * 50;
                updateRepeatLabel(delay);
                if (fromUser) prefs.edit().putInt(Prefs.REPEAT_DELAY, delay).apply();
            }
        });
        behaviour.addView(repeat, matchMargins(0, 0, 0, 4));

        // Sleep/Wake: one row, toggle + "›" for its settings.
        behaviour.addView(headerWithInfo("Sleep / Wake", "Sleep / Wake brightness",
                "When the Thor sleeps or the lid closes, both screens' brightness is saved. "
                + "On wake they are held dark briefly, then faded back up to the saved levels "
                + "instead of snapping on.\n\nLong-close reset (optional) wakes at a default "
                + "brightness if the Thor stayed closed longer than a time you choose."),
                matchMargins(0, 4, 0, 0));
        LinearLayout wakeRow = new LinearLayout(this);
        wakeRow.setGravity(Gravity.CENTER_VERTICAL);
        Switch gentleWake = new Switch(this);
        gentleWake.setText("Fade in on wake");
        gentleWake.setTextSize(14);
        gentleWake.setChecked(prefs.getBoolean(Prefs.GENTLE_WAKE, false));
        gentleWake.setOnCheckedChangeListener((button, checked) -> {
            if (wakeSettingsButton != null) {
                wakeSettingsButton.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
            if (suppressGentleWakeListener) return;
            if (checked && !requiredPermissionsReady()) {
                suppressGentleWakeListener = true;
                gentleWake.setChecked(false);
                suppressGentleWakeListener = false;
                Toast.makeText(this, "Set up permissions before enabling Sleep/Wake brightness.", Toast.LENGTH_LONG).show();
                return;
            }
            // Just switch it on/off; timings are changed from "Fade settings".
            prefs.edit().putBoolean(Prefs.GENTLE_WAKE, checked).apply();
        });
        wakeRow.addView(gentleWake, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        behaviour.addView(wakeRow, matchMargins(0, 0, 0, 0));
        // Small "Settings" pill under the toggle, only while the fade is on.
        Button configureWake = button("Fade settings");
        configureWake.setTextSize(13);
        configureWake.setTextColor(Palette.MAUVE);
        configureWake.setPadding(dp(12), dp(4), dp(12), dp(4));
        configureWake.setBackgroundTintList(ColorStateList.valueOf(Palette.surface0));
        configureWake.setOnClickListener(v -> {
            if (!requiredPermissionsReady()) {
                Toast.makeText(this, "Set up permissions before configuring Sleep/Wake brightness.", Toast.LENGTH_LONG).show();
                return;
            }
            showGentleWakeSettings(gentleWake);
        });
        configureWake.setVisibility(gentleWake.isChecked() ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams pill = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        pill.setMargins(0, dp(2), 0, dp(6));
        behaviour.addView(configureWake, pill);
        wakeSettingsButton = configureWake;

    }

    /** Reserve-modifier option; applies to every binding. */
    private void addReserveControls(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Palette.surface0);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        line.setMargins(0, dp(10), 0, dp(6));
        parent.addView(divider, line);
        Switch consume = new Switch(this);
        consume.setText("Reserve modifier buttons");
        consume.setTextSize(14);
        consume.setChecked(prefs.getBoolean(Prefs.CONSUME_MODIFIER, false));
        consume.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                prefs.edit().putBoolean(Prefs.CONSUME_MODIFIER, false).apply();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Reserve modifier buttons?")
                    .setMessage("Games will stop receiving the modifier of every binding ("
                            + modifierNames() + ") whenever it is pressed. Brighter and Dimmer "
                            + "buttons are already reserved only while a modifier is held.")
                    .setPositiveButton("Reserve", (dialog, which) ->
                            prefs.edit().putBoolean(Prefs.CONSUME_MODIFIER, true).apply())
                    .setNegativeButton("Cancel", (dialog, which) -> consume.setChecked(false))
                    .setOnCancelListener(dialog -> consume.setChecked(false))
                    .show();
        });
        parent.addView(consume, matchMargins(0, 0, 0, 0));
        TextView consumeHint = text("Applies to all bindings. Brighter and Dimmer are always "
                + "reserved while a modifier is held.", 11, false);
        consumeHint.setTextColor(Palette.SUBTEXT0);
        parent.addView(consumeHint, margins(0, 0, 0, 6));
    }

    /** At-a-glance status of everything the app depends on, plus quick tips. */
    private LinearLayout statusCard() {
        LinearLayout card = card();
        // Title with the overall Ready / Setup needed pill (tap for setup).
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(section("Status"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusPill = text("", 13, true);
        statusPill.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusPill.setFocusable(true);
        statusPill.setClickable(true);
        statusPill.setOnClickListener(v -> showSetupDialog());
        head.addView(statusPill);
        card.addView(head, matchMargins(0, 4, 0, 6));
        statusRows = new LinearLayout(this);
        statusRows.setOrientation(LinearLayout.VERTICAL);
        card.addView(statusRows, matchMargins(0, 0, 0, 0));
        refreshStatusCard();

        return card;
    }

    private String statusSignature = "";

    private void refreshStatusCard() {
        if (statusRows == null) return;
        boolean service = isAccessibilityServiceEnabled();
        boolean write = Settings.System.canWrite(this);
        boolean shizuku = ShizukuSupport.available();
        boolean dpad = prefs.getBoolean(Prefs.ROOT_AXES, false);
        boolean fade = prefs.getBoolean(Prefs.GENTLE_WAKE, false);
        boolean bright = prefs.getBoolean(Prefs.ENABLED, true);
        boolean vol = prefs.getBoolean(Prefs.VOL_ENABLED, false);
        String volMode = vol ? VOL_MODES[prefs.getInt(Prefs.VOL_MODE, 0)][0] : "Off";
        AudioManager audio = getSystemService(AudioManager.class);
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int main = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int bottom = VolumeLink.readSecondary(this);
        boolean conflict = volumeLinkConflict();
        String signature = bright + "," + service + "," + write + "," + shizuku + "," + dpad + "," + fade + ","
                + volMode + "," + main + "/" + max + "," + bottom + "," + conflict;
        if (signature.equals(statusSignature)) return;
        statusSignature = signature;
        statusRows.removeAllViews();
        // App-wide first, then each feature's own status.
        addStatusRow("Key detection", service ? "On" : "Needed", service);
        addStatusRow("Shizuku", shizuku ? "Connected" : "Off", shizuku);
        addStatusGroup("Brightness", false);
        addStatusRow("Brightness control", bright ? "On" : "Off", bright);
        addStatusRow("Brightness access", write ? "Allowed" : "Needed", write);
        addStatusRow("D-pad / Joystick", dpad ? "On" : "Off", dpad);
        addStatusRow("Fade in on wake", fade ? "On" : "Off", fade);
        addStatusGroup("Volume", false);
        addStatusRow("Volume control", vol ? "On" : "Off", vol);
        String modeName = VOL_MODES[prefs.getInt(Prefs.VOL_MODE, 0)][0];
        addStatusRow("Volume mode", modeName, vol);
        if (conflict) addStatusRow("Thor Volume Link", "Turn off", false);
    }

    /** Small subheading that splits the Status list into groups. */
    private void addStatusGroup(String title, boolean first) {
        TextView group = text(title, 13, true);
        group.setTextColor(Palette.SUBTEXT0);
        group.setLetterSpacing(0.05f);
        statusRows.addView(group, matchMargins(0, first ? 0 : 8, 0, 2));
        if (!first) {
            View line = new View(this);
            line.setBackgroundColor(Palette.surface0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(0, dp(10), 0, 0);
            statusRows.addView(line, statusRows.getChildCount() - 1, lp);
        }
    }

    private void addStatusRow(String label, String value, boolean good) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 14, false);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = text("\u25CF  " + value, 14, true);
        state.setTextColor(good ? Palette.GREEN : Palette.OVERLAY1);
        row.addView(state);
        statusRows.addView(row, matchMargins(0, 2, 0, 2));
    }

    /** Small standalone card holding the reset-to-defaults button. */
    private LinearLayout resetCard() {
        LinearLayout card = card();
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = section("Reset App");
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button reset = button("Reset to defaults");
        reset.setTextSize(13);
        reset.setTextColor(Palette.RED);
        reset.setBackgroundTintList(ColorStateList.valueOf(Palette.withAlpha(Palette.RED, 0x26)));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset to defaults?")
                .setMessage("Bindings, brightness steps, fade, D-pad and suspend settings go back "
                        + "to their defaults. Permissions and setup are kept.")
                .setPositiveButton("Reset", (dialog, which) -> resetToDefaults())
                .setNegativeButton("Cancel", null)
                .show());
        row.addView(reset);
        card.addView(row, matchMargins(0, 0, 0, 0));
        polish(card);
        return card;
    }

    private void resetToDefaults() {
        // Setup progress, the master switch and runtime state survive a reset.
        java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList(
                Prefs.ENABLED, Prefs.SETUP_GUIDE_SHOWN, Prefs.AWAITING_STEP_TWO,
                Prefs.BRIGHTNESS_GUIDE_SHOWN, Prefs.KEY_GUIDE_SHOWN,
                Prefs.ADVANCE_TO_KEY_PENDING, Prefs.ADVANCE_TO_BRIGHTNESS_PENDING,
                Prefs.SETUP_COMPLETE_SHOWN, Prefs.CONFLICT_WARNING_SHOWN,
                Prefs.CONFLICT_WARNING_SERVICES, Prefs.ROOT_LIMIT_ACK,
                Prefs.WAKE_CLOSED_AT, Prefs.CAPTURING, Prefs.SUSPEND_SERVICE_ACTIVE));
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (!keep.contains(key)) edit.remove(key);
        }
        edit.apply();
        Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show();
        recreate();
    }

    /** Live Top/Bottom brightness, refreshed while the app is in front. */
    /** Compact live Top/Bottom brightness in the header. */
    private void addLevelBars(LinearLayout parent) {
        topLevelText = text("", 13, true);
        bottomLevelText = text("", 13, true);
        topLevelText.setTextColor(Palette.YELLOW);
        bottomLevelText.setTextColor(Palette.YELLOW);
        topLevelBar = levelBar();
        bottomLevelBar = levelBar();
        // Sun or speaker: what the bars below are showing.
        levelIcon = new android.widget.ImageView(this);
        levelIcon.setImageTintList(ColorStateList.valueOf(Palette.SUBTEXT0));
        parent.addView(levelIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        ((LinearLayout.LayoutParams) levelIcon.getLayoutParams()).setMargins(dp(16), 0, 0, 0);
        // \u25B2 = top screen, \u25BC = bottom screen (words don't fit beside the tabs).
        parent.addView(headerLevel("\u25B2", topLevelBar, topLevelText), margins(8, 0, 0, 0));
        parent.addView(headerLevel("\u25BC", bottomLevelBar, bottomLevelText), margins(10, 0, 0, 0));
        refreshLevelBars();
    }

    private LinearLayout headerLevel(String label, android.widget.ProgressBar bar, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 12, false);
        name.setTextColor(Palette.SUBTEXT0);
        name.setSingleLine(true);
        row.addView(name);
        row.addView(bar, margins(6, 0, 6, 0));
        bar.getLayoutParams().width = dp(40);
        bar.getLayoutParams().height = dp(6);
        value.setSingleLine(true);
        row.addView(value);
        return row;
    }

    private TextView valueText() {
        TextView value = text("", 15, true);
        value.setTextColor(Palette.MAUVE);
        return value;
    }

    /** "Label ........ value" on one line. */
    private LinearLayout valueRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(label, 14, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        return row;
    }

    private android.widget.ProgressBar levelBar() {
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(Palette.YELLOW));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Palette.surface1));
        return bar;
    }

    private LinearLayout levelRow(String label, android.widget.ProgressBar bar, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 13, false);
        name.setTextColor(Palette.SUBTEXT0);
        row.addView(name, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(10), 1f));
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Reads both panels' current brightness (top: 0-255, bottom: 0-100). */
    private void refreshLevelBars() {
        if (topLevelBar == null) return;
        if (prefs.getInt(Prefs.UI_TAB, 0) == 1) {
            // Volume tab: the header shows both screens' volume instead.
            AudioManager audio = getSystemService(AudioManager.class);
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int main = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int bottom = VolumeLink.readSecondary(this);
            setLevelTint(Palette.LAVENDER);
            topLevelBar.setMax(max);
            topLevelBar.setProgress(main);
            bottomLevelBar.setMax(VolumeLink.SECONDARY_MAX);
            bottomLevelBar.setProgress(Math.max(0, bottom));
            topLevelText.setText(main + "/" + max);
            bottomLevelText.setText(bottom < 0 ? "\u2014" : bottom + "/" + VolumeLink.SECONDARY_MAX);
            return;
        }
        setLevelTint(Palette.YELLOW);
        topLevelBar.setMax(100);
        bottomLevelBar.setMax(100);
        int top;
        try {
            top = Math.round(Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS) * 100f / 255f);
        } catch (Settings.SettingNotFoundException e) {
            top = -1;
        }
        int bottom;
        int serviceBottom = BrightnessKeyService.lastBottomBrightness;
        if (serviceBottom >= 0) {
            bottom = Math.round(serviceBottom * 100f / 255f);
        } else try {
            bottom = Settings.System.getInt(getContentResolver(), "dual_screen_brightness_level");
        } catch (Settings.SettingNotFoundException e) {
            try {
                bottom = Settings.Secure.getInt(getContentResolver(), "dual_screen_brightness_level");
            } catch (Settings.SettingNotFoundException ignored) {
                bottom = -1;
            }
        }
        topLevelBar.setProgress(Math.max(0, top));
        bottomLevelBar.setProgress(Math.max(0, bottom));
        topLevelText.setText(top < 0 ? "\u2014" : top + "%");
        bottomLevelText.setText(bottom < 0 ? "\u2014" : bottom + "%");
    }

    private int levelTint;
    private android.widget.ImageView levelIcon;

    private void setLevelTint(int color) {
        if (levelTint == color) return;
        levelTint = color;
        if (levelIcon != null) {
            levelIcon.setImageResource(color == Palette.LAVENDER ? R.drawable.ic_speaker : R.drawable.ic_sun);
            levelIcon.setImageTintList(ColorStateList.valueOf(color));
        }
        ColorStateList tint = ColorStateList.valueOf(color);
        topLevelBar.setProgressTintList(tint);
        bottomLevelBar.setProgressTintList(tint);
        topLevelText.setTextColor(color);
        bottomLevelText.setTextColor(color);
    }

    /** One tappable chip ("Brighter / Vol +") that records its control when tapped. */
    private void addMappingRow(LinearLayout parent, String title, String preference,
                               int currentKey, boolean allowsVolume) {
        TextView label = text("", 14, true);
        label.setMaxLines(3);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setText(Prefs.B_UP_KEY.equals(preference) ? styledLabel(title, bindingBAssigned(true))
                : Prefs.B_DOWN_KEY.equals(preference) ? styledLabel(title, bindingBAssigned(false))
                : Prefs.isBindingB(preference) ? styledLabel(title, keyName(currentKey))
                : mappingLabel(title, currentKey));
        if (Prefs.MODIFIER.equals(preference)) modifierMappingLabel = label;
        if (Prefs.UP_KEY.equals(preference)) brighterMappingLabel = label;
        if (Prefs.DOWN_KEY.equals(preference)) dimmerMappingLabel = label;
        if (Prefs.B_MODIFIER.equals(preference)) bModifierMappingLabel = label;
        if (Prefs.B_UP_KEY.equals(preference)) bBrighterMappingLabel = label;
        if (Prefs.B_DOWN_KEY.equals(preference)) bDimmerMappingLabel = label;
        GradientDrawable chipBackground = new GradientDrawable();
        chipBackground.setColor(Palette.surface0);
        chipBackground.setCornerRadius(dp(CORNER));
        chipBackground.setStroke(dp(1), Palette.surface1);
        label.setBackground(chipBackground);
        label.setPadding(dp(8), dp(5), dp(6), dp(5));
        label.setMinHeight(dp(42));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setFocusable(true);
        label.setClickable(true);
        label.setOnClickListener(v -> {
            if (!requiredPermissionsReady()) {
                Toast.makeText(this, "Set up both required permissions before recording buttons", Toast.LENGTH_LONG).show();
                return;
            }
            showRecordPrompt(preference, label, title, allowsVolume);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(parent.getChildCount() == 0 ? 0 : dp(3), 0,
                parent.getChildCount() == 2 ? 0 : dp(3), 0);
        parent.addView(label, params);
    }

    private void updateRootCheckVisual(Button button) {
        boolean shizuku = ShizukuSupport.available();
        boolean root = !shizuku && hasRootAccess();
        boolean inferred = root || shizuku;
        button.setText(shizuku ? "Shizuku (ADB) - Enabled"
                : root ? "Root access - Enabled" : "Check privilege access");
        applyStatus(button, inferred);
    }

    /**
     * Fade timings and the long-close reset, laid out in two columns so the
     * whole popup fits the Thor's top screen.
     */
    private void showGentleWakeSettings(Switch gentleWake) {
        int duration = Math.max(100, Math.min(10000, prefs.getInt(Prefs.GENTLE_WAKE_DURATION, 5000)));
        int holdMs = Math.max(100, Math.min(5000, prefs.getInt(Prefs.WAKE_HOLD_DURATION, 1000)));
        int resetMinutes = Math.max(0, Math.min(1440,
                (int) (prefs.getLong(Prefs.WAKE_RESET_TIMEOUT, 120L * 60L * 1000L) / 60000L)));
        int resetLevelSaved = prefs.getInt(Prefs.WAKE_RESET_BRIGHTNESS, 128);

        // Left: the fade itself.
        LinearLayout fade = settingBox();
        fade.setPadding(dp(14), dp(10), dp(14), dp(10));
        fade.addView(section("Wake fade"), matchMargins(0, 0, 0, 4));
        TextView holdValue = valueText();
        fade.addView(valueRow("Stay dark for", holdValue), matchMargins(0, 0, 0, 0));
        SeekBar holdSlider = new SeekBar(this);
        holdSlider.setMax(49);
        holdSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                holdValue.setText(formatWakeDuration((progress + 1) * 100));
            }
        });
        holdSlider.setProgress((holdMs / 100) - 1);
        holdValue.setText(formatWakeDuration(holdMs));
        fade.addView(holdSlider, matchMargins(0, 0, 0, 8));
        TextView fadeValue = valueText();
        fade.addView(valueRow("Then fade up over", fadeValue), matchMargins(0, 0, 0, 0));
        SeekBar fadeSlider = new SeekBar(this);
        fadeSlider.setMax(99);
        fadeSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                fadeValue.setText(formatWakeDuration((progress + 1) * 100));
            }
        });
        fadeSlider.setProgress((duration / 100) - 1);
        fadeValue.setText(formatWakeDuration(duration));
        fade.addView(fadeSlider, matchMargins(0, 0, 0, 0));

        // Right: optional reset after a long close.
        LinearLayout reset = settingBox();
        reset.setPadding(dp(14), dp(10), dp(14), dp(10));
        reset.addView(section("Long-close reset"), matchMargins(0, 0, 0, 2));
        Switch resetEnabled = new Switch(this);
        resetEnabled.setText("Reset if closed for a while");
        resetEnabled.setTextSize(14);
        resetEnabled.setChecked(prefs.getBoolean(Prefs.WAKE_RESET_ENABLED, false));
        reset.addView(resetEnabled, matchMargins(0, 0, 0, 4));
        LinearLayout resetOptions = new LinearLayout(this);
        resetOptions.setOrientation(LinearLayout.VERTICAL);
        TextView afterValue = valueText();
        resetOptions.addView(valueRow("After", afterValue), matchMargins(0, 0, 0, 0));
        SeekBar resetTime = new SeekBar(this);
        resetTime.setMax(152);
        resetTime.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                afterValue.setText(formatLongClose(longCloseMinutes(progress)));
            }
        });
        resetTime.setProgress(longCloseProgress(resetMinutes));
        afterValue.setText(formatLongClose(resetMinutes));
        resetOptions.addView(resetTime, matchMargins(0, 0, 0, 8));
        TextView levelValue = valueText();
        resetOptions.addView(valueRow("Reset to", levelValue), matchMargins(0, 0, 0, 0));
        SeekBar resetLevel = new SeekBar(this);
        resetLevel.setMax(254);
        resetLevel.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                levelValue.setText(Math.round((progress + 1) * 100f / 255f) + "%");
            }
        });
        resetLevel.setProgress(resetLevelSaved - 1);
        levelValue.setText(Math.round(resetLevelSaved * 100f / 255f) + "%");
        resetOptions.addView(resetLevel, matchMargins(0, 0, 0, 0));
        resetOptions.setAlpha(resetEnabled.isChecked() ? 1f : 0.4f);
        resetTime.setEnabled(resetEnabled.isChecked());
        resetLevel.setEnabled(resetEnabled.isChecked());
        resetEnabled.setOnCheckedChangeListener((button, checked) -> {
            resetOptions.setAlpha(checked ? 1f : 0.4f);
            resetTime.setEnabled(checked);
            resetLevel.setEnabled(checked);
        });
        reset.addView(resetOptions, matchMargins(0, 0, 0, 0));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(20), dp(4), dp(20), 0);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        right.setMargins(dp(8), 0, 0, 0);
        columns.addView(fade, left);
        columns.addView(reset, right);
        polish(columns);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(columns);

        AlertDialog wakeDialog = new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Fade settings"))
                .setView(scroll)
                .setPositiveButton("Save", (dialog, which) -> saveGentleWake(
                        (fadeSlider.getProgress() + 1) * 100,
                        (holdSlider.getProgress() + 1) * 100,
                        longCloseMinutes(resetTime.getProgress()) * 60000L,
                        resetLevel.getProgress() + 1,
                        resetEnabled.isChecked()))
                .setNegativeButton("Cancel", null)
                .create();
        wakeDialog.setOnShowListener(d -> wakeDialog.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE));
        wakeDialog.show();
        if (wakeDialog.getWindow() != null) {
            wakeDialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private String formatWakeDuration(int durationMs) {
        return String.format(java.util.Locale.ROOT, "%.1f seconds", durationMs / 1000f);
    }

    private String formatLongClose(int minutes) {
        if (minutes < 60) return String.format(java.util.Locale.ROOT, "%02d Minutes", minutes);
        return String.format(java.util.Locale.ROOT, "%02d hour%s %02d Minutes", minutes / 60,
                minutes / 60 == 1 ? "" : "s",
                minutes % 60);
    }

    private int longCloseMinutes(int progress) {
        return progress <= 60 ? progress : 60 + (progress - 60) * 15;
    }

    private int longCloseProgress(int minutes) {
        return minutes <= 60 ? minutes : 60 + Math.round((minutes - 60) / 15f);
    }

    private LinearLayout settingBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.surface0);
        bg.setCornerRadius(dp(CORNER));
        bg.setStroke(dp(1), Palette.surface1);
        box.setBackground(bg);
        return box;
    }

    private void moveIntoBox(LinearLayout parent, LinearLayout box, View... views) {
        for (View view : views) {
            parent.removeView(view);
            box.addView(view);
        }
    }

    private void saveGentleWake(int duration, int holdDuration, long resetTimeout, int resetBrightness,
                                boolean resetEnabled) {
        prefs.edit().putBoolean(Prefs.GENTLE_WAKE, true)
                .putInt(Prefs.GENTLE_WAKE_DURATION, duration)
                .putInt(Prefs.WAKE_HOLD_DURATION, holdDuration)
                .putLong(Prefs.WAKE_RESET_TIMEOUT, resetTimeout)
                .putInt(Prefs.WAKE_RESET_BRIGHTNESS, resetBrightness)
                .putBoolean(Prefs.WAKE_RESET_ENABLED, resetEnabled).apply();
    }

    private boolean requiredPermissionsReady() {
        return Settings.System.canWrite(this) && isAccessibilityServiceEnabled();
    }

    /** One subtle corner radius (dp) for every card, button, pill and ring. */
    private static final int CORNER = 6;

    private View recordDetails;
    private TextView recordSubText;

    private void showRecordPrompt(String preference, TextView label, String title,
                                   boolean allowsVolume) {
        boolean rootDirection = prefs.getBoolean(Prefs.ROOT_AXES, false)
                && Prefs.isDirectional(preference);
        String prompt = rootDirection ? "Move the D-pad or a stick" : "Press a button";
        String forWhat = Prefs.isModifier(preference) ? "to use as the modifier"
                : Prefs.UP_KEY.equals(preference) || Prefs.B_UP_KEY.equals(preference)
                ? "to make the screen brighter" : "to make the screen dimmer";

        recordDialogCompleted = false;
        recordDialogMessage = text(prompt, 22, true);
        recordDialogMessage.setTextColor(Palette.TEXT);
        recordDialogMessage.setGravity(Gravity.CENTER);
        TextView sub = text(forWhat, 15, false);
        recordSubText = sub;
        sub.setTextColor(Palette.SUBTEXT1);
        sub.setGravity(Gravity.CENTER);
        TextView hint = text(Prefs.isModifier(preference)
                ? "Face, shoulder, stick-click, Start or Select"
                : "Volume, face, shoulder, stick-click, Start or Select"
                + (prefs.getBoolean(Prefs.ROOT_AXES, false) ? ", D-pad or stick" : ""), 13, false);
        hint.setTextColor(Palette.SUBTEXT0);
        hint.setGravity(Gravity.CENTER);

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(10000);
        bar.setProgress(10000);
        bar.setProgressTintList(ColorStateList.valueOf(Palette.MAUVE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Palette.surface1));
        TextView seconds = text("10s", 13, true);
        seconds.setTextColor(Palette.SUBTEXT0);
        LinearLayout timer = new LinearLayout(this);
        timer.setGravity(Gravity.CENTER_VERTICAL);
        timer.addView(bar, new LinearLayout.LayoutParams(0, dp(6), 1f));
        timer.addView(seconds, margins(12, 0, 0, 0));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(sub, matchMargins(0, 4, 0, 14));
        details.addView(timer, matchMargins(0, 0, 0, 10));
        details.addView(hint, matchMargins(0, 0, 0, 0));
        recordDetails = details;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(28), dp(4), dp(28), dp(8));
        body.addView(recordDialogMessage, matchMargins(0, 0, 0, 0));
        body.addView(details, matchMargins(0, 0, 0, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        // After a tap Android is in touch mode, and the first D-pad press is
        // spent leaving it (and highlighting a view) instead of being
        // delivered. Leave touch mode up front, parking focus on the blank
        // body, so the very first press records.
        body.setFocusable(true);
        body.setFocusableInTouchMode(true);

        recordDialog = new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Record " + title))
                .setView(scroll)
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (capturing) endCapture(false, 0);
                })
                .create();
        // Back, tapping outside, or Cancel all abandon the recording.
        recordDialog.setCancelable(true);
        recordDialog.setCanceledOnTouchOutside(true);
        recordDialog.setOnCancelListener(dialog -> {
            if (capturing) endCapture(false, 0);
        });
        recordDialog.setOnKeyListener((dialog, keyCode, keyEvent) -> {
            if (recordDialogCompleted) return true;
            if (keyEvent.getAction() == KeyEvent.ACTION_DOWN
                    && keyEvent.getRepeatCount() == 0
                    && keyCode != KeyEvent.KEYCODE_BACK) {
                endCapture(true, keyCode);
                return true;
            }
            return false;
        });
        recordDialog.setOnShowListener(dialog -> {
            if (recordDialog.getWindow() != null) {
                recordDialog.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            beginCapture(preference, label, title, allowsVolume);
        });
        recordDialog.show();
        body.requestFocusFromTouch();
        Button cancelButton = recordDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (cancelButton != null) {
            cancelButton.setFocusable(false); // touch only, see above
            cancelButton.setFocusableInTouchMode(false);
        }
        if (recordDialog.getWindow() != null) {
            int width = Math.min(dp(520), (int) (getResources().getDisplayMetrics().widthPixels * 0.6f));
            recordDialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final long deadline = System.currentTimeMillis() + 10000;
        recordCountdown = new Runnable() {
            @Override public void run() {
                if (!capturing || recordDialog == null || !recordDialog.isShowing()) return;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    endCapture(false, 0);
                } else {
                    bar.setProgress((int) left);
                    seconds.setText((int) Math.ceil(left / 1000.0) + "s");
                    recordCountdownHandler.postDelayed(this, 50);
                }
            }
        };
        recordCountdownHandler.post(recordCountdown);
    }

    private void finishRecordDialog(String message) {
        if (recordDialogMessage == null || recordDialog == null) return;
        if (recordCountdown != null) recordCountdownHandler.removeCallbacks(recordCountdown);
        recordDialogCompleted = true;
        if (recordDetails != null) recordDetails.setVisibility(View.GONE);
        Button cancel = recordDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (cancel != null) cancel.setVisibility(View.INVISIBLE);
        recordDialogMessage.setText(message);
        recordDialogMessage.setTextSize(20);
        recordDialogMessage.setTextColor(Palette.GREEN);
        recordDialogMessage.setPadding(0, dp(8), 0, dp(8));
        recordDialogMessage.postDelayed(() -> {
            if (recordDialog != null && recordDialog.isShowing()) recordDialog.dismiss();
            recordDialog = null;
            recordDialogMessage = null;
        }, 1500);
    }

    private void beginCapture(String preference, TextView label, String title,
                              boolean allowsVolume) {
        BrightnessKeyService.captureListener = this::onServiceCapturedKey;
        if (prefs.getBoolean(Prefs.ROOT_AXES, false) && Prefs.isDirectional(preference)) {
            beginRootDirectionCapture(preference, label, title);
            return;
        }
        capturing = true;
        capturePreference = preference;
        captureLabel = label;
        captureTitle = title;
        captureAllowsVolume = allowsVolume;
        prefs.edit().putBoolean(Prefs.CAPTURING, true).apply();
    }

    private void beginRootDirectionCapture(String preference, TextView label, String title) {
        BrightnessKeyService.captureListener = this::onServiceCapturedKey;
        if (!hasRootAccess() && !ShizukuSupport.available()) {
            Toast.makeText(this, "Root access or authorized Shizuku is required to record a D-pad or stick direction", Toast.LENGTH_LONG).show();
            return;
        }
        stopRootDirectionRecorder();
        capturing = true;
        capturingRootDirection = true;
        capturePreference = preference;
        captureLabel = label;
        captureTitle = title;
        captureAllowsVolume = false;
        prefs.edit().putBoolean(Prefs.CAPTURING, true).apply();
        String rootCaptureNote = Prefs.MODIFIER.equals(preference)
                ? " Note: using a D-pad or stick direction as the modifier is not recommended because games may also receive that input."
                : "";
        rootDirectionRecorder = new RootInputMonitor(new Handler(Looper.getMainLooper()),
                new RootInputMonitor.Listener() {
                    @Override public void onDirection(int encoded) {
                        if (encoded != 0 && capturingRootDirection) {
                            saveRootDirection(encoded / 10, encoded % 10);
                        }
                    }
                    @Override public void onStopped() { }
                });
        rootDirectionRecorder.start(0);
    }

    private void saveRootDirection(int source, int direction) {
        if (rootDirectionConflict(source, direction)) {
            Toast.makeText(this, "This direction is already used by another brightness mapping", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean b = Prefs.isBindingB(capturePreference);
        String brightSourceKey = b ? Prefs.ROOT_B_BRIGHT_SOURCE : Prefs.ROOT_BRIGHT_SOURCE;
        String brightDirectionKey = b ? Prefs.ROOT_B_BRIGHT_DIRECTION : Prefs.ROOT_BRIGHT_DIRECTION;
        String dimSourceKey = b ? Prefs.ROOT_B_DIM_SOURCE : Prefs.ROOT_DIM_SOURCE;
        String dimDirectionKey = b ? Prefs.ROOT_B_DIM_DIRECTION : Prefs.ROOT_DIM_DIRECTION;
        boolean recordingBright = Prefs.UP_KEY.equals(capturePreference) || Prefs.B_UP_KEY.equals(capturePreference);
        boolean recordingDim = Prefs.DOWN_KEY.equals(capturePreference) || Prefs.B_DOWN_KEY.equals(capturePreference);
        boolean swapWithDim = recordingBright
                && source == prefs.getInt(dimSourceKey, -1)
                && direction == prefs.getInt(dimDirectionKey, 0);
        boolean swapWithBright = recordingDim
                && source == prefs.getInt(brightSourceKey, -1)
                && direction == prefs.getInt(brightDirectionKey, 0);
        stopRootDirectionRecorder();
        capturingRootDirection = false;
        capturing = false;
        SharedPreferences.Editor editor = prefs.edit().putBoolean(Prefs.CAPTURING, false);
        if (Prefs.MODIFIER.equals(capturePreference)) {
            editor.putInt(Prefs.ROOT_MODIFIER_DIRECTION, direction)
                    .putInt(Prefs.ROOT_MODIFIER_SOURCE, source);
        } else {
            String directionPreference = recordingBright ? brightDirectionKey : dimDirectionKey;
            String sourcePreference = recordingBright ? brightSourceKey : dimSourceKey;
            editor.putInt(directionPreference, direction).putInt(sourcePreference, source);
            if (swapWithDim) {
                editor.putInt(dimDirectionKey, prefs.getInt(brightDirectionKey, 0))
                        .putInt(dimSourceKey, prefs.getInt(brightSourceKey, -1));
            } else if (swapWithBright) {
                editor.putInt(brightDirectionKey, prefs.getInt(dimDirectionKey, 0))
                        .putInt(brightSourceKey, prefs.getInt(dimSourceKey, -1));
            }
        }
        editor.apply();
        updateRootMappingLabels();
        // The record row can be the currently active view while the monitor
        // callback is completing. Update it explicitly so the new modifier or
        // brightness direction is visible without leaving/reopening the page.
        if (captureLabel != null && !Prefs.isBindingB(capturePreference)) {
            captureLabel.setText(mappingLabel(captureTitle,
                    Prefs.MODIFIER.equals(capturePreference)
                            ? prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1)
                            : Prefs.UP_KEY.equals(capturePreference)
                            ? prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP)
                            : prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN)));
            captureLabel.requestLayout();
            captureLabel.invalidate();
        }
        // Re-read the committed preferences once the row/layout pass has
        // completed; this also covers the dual-screen UI retaining a stale
        // text snapshot during the monitor callback.
        new Handler(Looper.getMainLooper()).postDelayed(this::updateRootMappingLabels, 120);
        updateRootStatus(true);
        setStaticCaptureMessage();
        finishRecordDialog("Saved the " + captureTitle + " as " + sourceName(source) + " " + rootDirectionName(direction) + ".");
    }

    private boolean rootDirectionConflict(int source, int direction) {
        if (Prefs.isBindingB(capturePreference)) return false;
        int brightSource = prefs.getInt(Prefs.ROOT_BRIGHT_SOURCE, -1);
        int brightDirection = prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0);
        int dimSource = prefs.getInt(Prefs.ROOT_DIM_SOURCE, -1);
        int dimDirection = prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0);
        int modifierSource = prefs.getInt(Prefs.ROOT_MODIFIER_SOURCE, -1);
        int modifierDirection = prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0);
        boolean matchesBright = !Prefs.UP_KEY.equals(capturePreference)
                && source == brightSource && direction == brightDirection;
        boolean matchesDim = !Prefs.DOWN_KEY.equals(capturePreference)
                && source == dimSource && direction == dimDirection;
        // A stick/D-pad used as the modifier must remain held. Any direction
        // from that same source would release or replace the modifier, so the
        // whole source is reserved while root modifier mapping is active.
        boolean matchesModifier = !Prefs.MODIFIER.equals(capturePreference)
                && source == modifierSource && modifierDirection != 0;
        if (Prefs.MODIFIER.equals(capturePreference)) {
            return matchesBright || matchesDim;
        }
        if (Prefs.UP_KEY.equals(capturePreference)) {
            // An exact Dimmer match is swapped below; only the modifier source
            // remains a hard conflict.
            return matchesModifier;
        }
        // An exact Brighter match is swapped below; only the modifier source
        // remains a hard conflict.
        return matchesModifier;
    }

    private void stopRootDirectionRecorder() {
        if (rootDirectionRecorder != null) {
            rootDirectionRecorder.stop();
            rootDirectionRecorder = null;
        }
    }

    /** Short tick when a control is tapped or activated with the controller. */
    private long lastHaptic;

    private void haptic(View view) {
        if (view == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHaptic < 120) return; // one tick per action
        lastHaptic = now;
        // The Thor's motor only supports CLICK/TICK-style effects; the view
        // haptic constants map to TEXTURE_TICK there, which it can't play.
        android.os.Vibrator vibrator = getSystemService(android.os.Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(
                    android.os.VibrationEffect.EFFECT_TICK));
        }
    }

    /** No click sounds anywhere in the app; haptics replace them. */
    private void muteClicks(View view) {
        view.setSoundEffectsEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) muteClicks(group.getChildAt(i));
        }
    }

    /** Deepest enabled, clickable view under a screen point. */
    private View clickableAt(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE) return null;
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = clickableAt(group.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return view.isEnabled() && (view.isClickable() || view instanceof SeekBar) ? view : null;
    }

    private View touchDownView;
    private float touchDownX, touchDownY;
    private boolean touchMoved;

    /** Buzz only on a real tap: lifted on the same control without scrolling. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        float x = event.getRawX(), y = event.getRawY();
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                touchDownView = clickableAt(getWindow().getDecorView(), (int) x, (int) y);
                touchDownX = x;
                touchDownY = y;
                touchMoved = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
                if (Math.abs(x - touchDownX) > slop || Math.abs(y - touchDownY) > slop) touchMoved = true;
                break;
            case android.view.MotionEvent.ACTION_UP:
                if (!touchMoved && touchDownView != null
                        && clickableAt(getWindow().getDecorView(), (int) x, (int) y) == touchDownView) {
                    haptic(touchDownView);
                }
                touchDownView = null;
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                touchDownView = null;
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!capturing && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int key = event.getKeyCode();
            View focused = getCurrentFocus();
            if ((key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER) && focused != null && focused.isEnabled()
                    && focused.isClickable()) {
                haptic(focused);
            }
        }
        if (!capturing) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            endCapture(false, 0);
            return true;
        }
        if (Prefs.isModifier(capturePreference) && isDpadKey(code)) {
            Toast.makeText(this, "Directional controls cannot be used as the modifier", Toast.LENGTH_LONG).show();
            return true;
        }
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                && Prefs.isDirectional(capturePreference)
                && isDpadKey(code)) {
            // Root capture must identify the physical D-pad source; never
            // fall back to saving Android's generic DPAD key name.
            return true;
        }
        if (capturingRootDirection) {
            // Root capture listens to axes in parallel with normal key events.
            // Ignore Android's synthetic D-pad events (the root monitor will
            // identify their real D-pad/stick source), but still allow every
            // face, shoulder, click, Start, Select, and other button through.
            if (!isDpadKey(code) && code != KeyEvent.KEYCODE_POWER
                    && code != KeyEvent.KEYCODE_HOME) {
                endCapture(true, code);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME
                || (!captureAllowsVolume && (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN))) {
            Toast.makeText(this, "That system key cannot be used here", Toast.LENGTH_SHORT).show();
            return true;
        }
        endCapture(true, code);
        return true;
    }

    /** A key the accessibility service caught while the Record popup is open. */
    private void onServiceCapturedKey(int keyCode) {
        runOnUiThread(() -> {
            if (!capturing || recordDialogCompleted) return;
            if (keyCode == KeyEvent.KEYCODE_BACK) endCapture(false, 0);
            else endCapture(true, keyCode);
        });
    }

    private void endCapture(boolean save, int code) {
        if (save && isDpadKey(code) && !prefs.getBoolean(Prefs.ROOT_AXES, false)) {
            // The Thor's D-pad never reaches the service as a key, so a D-pad
            // saved here would never fire. Explain and keep waiting.
            if (recordSubText != null) {
                recordSubText.setText("D-pad needs \u201CEnable D-pad / Joystick\u201D turned on.\n"
                        + "Turn it on in Input, or press another button.");
                recordSubText.setTextColor(Palette.PEACH);
            }
            return;
        }
        if (!(save && Prefs.MODIFIER.equals(capturePreference)
                && prefs.getBoolean(Prefs.ROOT_AXES, false) && isDpadKey(code))) {
            BrightnessKeyService.captureListener = null;
        }
        if (save && Prefs.MODIFIER.equals(capturePreference)
                && prefs.getBoolean(Prefs.ROOT_AXES, false) && isDpadKey(code)) {
            Toast.makeText(this, "Directional controls cannot be used as the modifier", Toast.LENGTH_LONG).show();
            return;
        }
        stopRootDirectionRecorder();
        capturingRootDirection = false;
        capturing = false;
        prefs.edit().putBoolean(Prefs.CAPTURING, false).apply();
        if (save) {
            String swappedTitle = saveMappingWithSwap(code);
            if (swappedTitle == null) {
                setStaticCaptureMessage();
            } else {
                setStaticCaptureMessage();
            }
            if (Prefs.MODIFIER.equals(capturePreference)) {
                prefs.edit().remove(Prefs.ROOT_MODIFIER_SOURCE)
                        .remove(Prefs.ROOT_MODIFIER_DIRECTION).apply();
            }
            if (Prefs.isDirectional(capturePreference)) {
                // A recorded button switches this binding back to buttons:
                // D-pad directions would otherwise keep overriding it.
                boolean second = Prefs.isBindingB(capturePreference);
                prefs.edit()
                        .remove(second ? Prefs.ROOT_B_BRIGHT_SOURCE : Prefs.ROOT_BRIGHT_SOURCE)
                        .remove(second ? Prefs.ROOT_B_BRIGHT_DIRECTION : Prefs.ROOT_BRIGHT_DIRECTION)
                        .remove(second ? Prefs.ROOT_B_DIM_SOURCE : Prefs.ROOT_DIM_SOURCE)
                        .remove(second ? Prefs.ROOT_B_DIM_DIRECTION : Prefs.ROOT_DIM_DIRECTION)
                        .apply();
                updateBindingSummaries();
            }
            // Refresh after clearing any previous root modifier mapping so the
            // visible label always reflects the newly saved control.
            Prefs.migrateDpadButtons(prefs);
            updateRootMappingLabels();
            updateSafetyText();
            finishRecordDialog(swappedTitle == null
                    ? "Saved the " + captureTitle + " as " + keyName(code) + "."
                    : "Saved the " + captureTitle + " as " + keyName(code) + ".");
        } else {
            setStaticCaptureMessage();
            if (recordCountdown != null) recordCountdownHandler.removeCallbacks(recordCountdown);
            if (recordDialog != null) recordDialog.dismiss();
            recordDialog = null;
            recordDialogMessage = null;
        }
        setStaticCaptureMessage();
    }

    private void setStaticCaptureMessage() {
        if (captureMessage == null) return;
        captureMessage.setText("Use Volume, face, shoulder, stick-click, Start or Select. With root or Shizuku, Binding 1 can also record D-pad or stick directions.");
        captureMessage.setTextColor(Palette.SUBTEXT0);
    }

    private String saveMappingWithSwap(int code) {
        int oldCaptureCode = prefs.getInt(capturePreference, Prefs.defaultKey(capturePreference));
        String conflictingPreference = conflictingPreferenceFor(code);
        SharedPreferences.Editor editor = prefs.edit().putInt(capturePreference, code);
        if (conflictingPreference != null) {
            editor.putInt(conflictingPreference, oldCaptureCode);
        }
        editor.apply();
        return titleForPreference(conflictingPreference);
    }

    /**
     * Finds a mapping that already uses {@code code} and would clash with the
     * one being recorded. Keys may repeat across bindings (both can use
     * volume), but within a binding every key is unique, and a key that is a
     * modifier in one binding cannot be any key in the other.
     */
    private String conflictingPreferenceFor(int code) {
        boolean recordingB = Prefs.isBindingB(capturePreference);
        String[] own = recordingB
                ? new String[]{Prefs.B_MODIFIER, Prefs.B_UP_KEY, Prefs.B_DOWN_KEY}
                : new String[]{Prefs.MODIFIER, Prefs.UP_KEY, Prefs.DOWN_KEY};
        String[] other = recordingB
                ? new String[]{Prefs.MODIFIER, Prefs.UP_KEY, Prefs.DOWN_KEY}
                : new String[]{Prefs.B_MODIFIER, Prefs.B_UP_KEY, Prefs.B_DOWN_KEY};
        for (String preference : own) {
            if (!preference.equals(capturePreference)
                    && prefs.getInt(preference, Prefs.defaultKey(preference)) == code) {
                return preference;
            }
        }
        if (!prefs.getBoolean(Prefs.B_ENABLED, false)) return null;
        boolean recordingModifier = Prefs.isModifier(capturePreference);
        for (String preference : other) {
            boolean otherIsModifier = Prefs.isModifier(preference);
            if ((recordingModifier || otherIsModifier)
                    && prefs.getInt(preference, Prefs.defaultKey(preference)) == code) {
                return preference;
            }
        }
        return null;
    }

    private String titleForPreference(String preference) {
        if (preference == null) return null;
        String prefix = Prefs.isBindingB(preference) ? "Binding B " : "";
        if (Prefs.isModifier(preference)) return prefix + "Modifier";
        if (Prefs.UP_KEY.equals(preference) || Prefs.B_UP_KEY.equals(preference)) return prefix + "Brighter";
        return prefix + "Dimmer";
    }

    @Override
    protected void onResume() {
        super.onResume();
        levelHandler.removeCallbacks(levelPoller);
        levelHandler.post(levelPoller);
        if (permissionStatus != null) {
            updatePermissionStatus();
            updateSetupButtons();
        }
        if (checkRootButton != null) refreshPrivilegeButton();
        if (prefs.getBoolean(Prefs.ADVANCE_TO_KEY_PENDING, false)
                && Settings.System.canWrite(this)) {
            prefs.edit().putBoolean(Prefs.ADVANCE_TO_KEY_PENDING, false).apply();
            if (!isAccessibilityServiceEnabled()) showKeyTransitionPrompt();
        }
        if (prefs.getBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, false)
                && isAccessibilityServiceEnabled()) {
            prefs.edit().putBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, false).apply();
            if (!Settings.System.canWrite(this)) showBrightnessTransitionPrompt();
        }
        if (Settings.System.canWrite(this) && isAccessibilityServiceEnabled()
                && !prefs.getBoolean(Prefs.SETUP_COMPLETE_SHOWN, false)) {
            prefs.edit().putBoolean(Prefs.SETUP_COMPLETE_SHOWN, true).apply();
            new AlertDialog.Builder(this)
                    .setCustomTitle(centeredDialogTitle("\u26A1 Setup Complete! \u26A1"))
                    .setMessage("Brightness control is ready.\n\nRoot access or Shizuku is required if you want to use the D-pad or analogue sticks. To do this, follow the steps below:\n\n- Tap \"Check privilege access\".\n- Allow root access or Shizuku access when Android asks.\n- Turn on \"Enable D-pad / Joystick\".\n- Tap a Brighter or Dimmer \"Record\" button, then move the control and direction you want to use.\n\nRecord the Bright and Dim controls separately. Each can use its own D-pad or stick direction.")
                    .setPositiveButton("Got it", null).show();
        }
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));
        }
        // Privileged features must not remain enabled after Root/Shizuku is
        // revoked while the app is stopped or in the background.
        if (!hasRootAccess() && !ShizukuSupport.available()) {
            boolean axes = prefs.getBoolean(Prefs.ROOT_AXES, false);
            boolean suspend = prefs.getBoolean(Prefs.SUSPEND_SERVICE, false);
            if (axes || suspend) {
                prefs.edit().putBoolean(Prefs.ROOT_AXES, false)
                        .putBoolean(Prefs.SUSPEND_SERVICE, false).apply();
                if (suspendServicesSwitch != null) suspendServicesSwitch.setChecked(false);
                updateRootStatus(false);
            }
        }
        // Refresh known conflict services when the app returns to the
        // foreground. This also catches ThorVolumeLink being enabled after
        // the initial root setup.
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                || prefs.getBoolean(Prefs.SUSPEND_SERVICE, false)) {
            autoConfigureVolumeLinkSuspension();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && (prefs.getBoolean(Prefs.ROOT_AXES, false)
                        || prefs.getBoolean(Prefs.SUSPEND_SERVICE, false))) {
                    autoConfigureVolumeLinkSuspension();
                }
            }, 1500);
        }
        if (prefs.getBoolean(Prefs.AWAITING_STEP_TWO, false)
                && Settings.System.canWrite(this) && !isAccessibilityServiceEnabled()) {
            prefs.edit().putBoolean(Prefs.AWAITING_STEP_TWO, false).apply();
            showStepTwoGuide();
        }
    }

    private void autoConfigureVolumeLinkSuspension() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return;
        java.util.Set<String> matches = new java.util.HashSet<>();
        for (String id : enabled.split(":")) {
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            if (!id.startsWith(getPackageName() + "/")
                    && !prefs.getStringSet(Prefs.SUSPEND_SERVICE_IGNORED, java.util.Collections.emptySet()).contains(id)
                    && (lower.contains("thorvolume") || lower.contains("volumecontrol"))) {
                matches.add(id);
            }
        }
        if (!matches.isEmpty()) {
            java.util.Set<String> selected = selectedSuspendServices();
            selected.addAll(matches);
            prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_COMPONENTS, selected)
                    .putString(Prefs.SUSPEND_SERVICE_COMPONENT, selected.iterator().next())
                    .putBoolean(Prefs.SUSPEND_SERVICE, true).apply();
            if (suspendServicesSwitch != null) suspendServicesSwitch.setChecked(true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    @Override
    protected void onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        super.onStop();
    }

    @Override
    protected void onPause() {
        if (capturing) endCapture(false, 0);
        levelHandler.removeCallbacks(levelPoller);
        super.onPause();
    }

    private void updatePermissionStatus() {
        boolean write = Settings.System.canWrite(this);
        boolean service = isAccessibilityServiceEnabled();
        String status;
        if (write && service) {
            status = "";
            status = "Ready - permissions enabled";
        } else if (!write && !service) {
            status = "Brightness and key detection needed";
        } else if (!write) {
            status = "Brightness permission needed";
        } else {
            status = "Key detection needed";
        }
        permissionStatus.setText(status);
        permissionStatus.setTextColor(write && service ? Palette.GREEN : Palette.PEACH);
    }

    private void updateSetupButtons() {
        boolean write = Settings.System.canWrite(this);
        boolean service = isAccessibilityServiceEnabled();
        updateSetupButton(brightnessPermissionButton, "Set up permissions", write && service);
        if (setupHint != null) setupHint.setVisibility(write && service ? View.GONE : View.VISIBLE);
        updateStatusPill(write && service);
        refreshStatusCard();
        if (brightnessStatus != null) {
            brightnessStatus.setText("Brightness permission - " + (write ? "Enabled" : "Needed"));
            keyStatus.setText("Key detection - " + (service ? "Enabled" : "Needed"));
            brightnessStatus.setTextColor(write ? Palette.GREEN : Palette.YELLOW);
            keyStatus.setTextColor(service ? Palette.GREEN : Palette.YELLOW);
        }
    }

    private void updateSetupButton(Button button, String label, boolean available) {
        if (button == null) return;
        button.setTextSize(button == checkRootButton ? 14 : button == brightnessPermissionButton ? 14 : 16);
        button.setText(available
                ? (button == brightnessPermissionButton ? "\u2713  Permissions ready" : label + " - Enabled")
                : label + " - Needed");
        applyStatus(button, available);
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName ours = new ComponentName(this, BrightnessKeyService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName component = ComponentName.unflattenFromString(splitter.next());
            if (ours.equals(component)) return true;
        }
        return false;
    }

    private void maybeShowSetupGuide() {
        if (prefs.getBoolean(Prefs.SETUP_GUIDE_SHOWN, false)
                || (Settings.System.canWrite(this) && isAccessibilityServiceEnabled())) {
            return;
        }
        new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Welcome to Thor's Lightning! \u26A1"))
                .setMessage("This app lets you use the AYN Thor's controller inputs to adjust the screen brightness!\n\nTo get started, tap \"Set up permissions\" to allow the app to function correctly.")
                .setPositiveButton("Got it", (dialog, which) -> {
                    prefs.edit().putBoolean(Prefs.SETUP_GUIDE_SHOWN, true).apply();
                    new Handler(Looper.getMainLooper()).postDelayed(this::maybeShowConflictWarning, 250);
                })
                .show();
    }

    private void showStepTwoGuide() {
        new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Step 2 - Enable key detection"))
                .setMessage("Open \"Downloaded apps\", select \"Thor's Lightning controls\", toggle it on, then tap \"Allow\" when Android asks.")
                .setPositiveButton("Open Accessibility", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Later", null)
                .show();
    }

    private java.util.Set<String> knownConflictServicesEnabled() {
        java.util.Set<String> matches = new java.util.HashSet<>();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return matches;
        String ours = getPackageName().toLowerCase(java.util.Locale.ROOT);
        for (String id : enabled.split(":")) {
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith(ours + "/")
                    && (lower.contains("thorvolume") || lower.contains("volumecontrol"))) {
                matches.add(id);
            }
        }
        return matches;
    }

    private void maybeShowConflictWarning() {
        if (!prefs.getBoolean(Prefs.SETUP_GUIDE_SHOWN, false)) return;
        java.util.Set<String> detected = knownConflictServicesEnabled();
        java.util.Set<String> acknowledged = prefs.getStringSet(
                Prefs.CONFLICT_WARNING_SERVICES, java.util.Collections.emptySet());
        java.util.Set<String> newServices = new java.util.HashSet<>(detected);
        newServices.removeAll(acknowledged);
        if (newServices.isEmpty()) return;
        java.util.Set<String> updatedAcknowledged = new java.util.HashSet<>(acknowledged);
        updatedAcknowledged.addAll(newServices);
        prefs.edit().putBoolean(Prefs.CONFLICT_WARNING_SHOWN, true)
                .putStringSet(Prefs.CONFLICT_WARNING_SERVICES, updatedAcknowledged).apply();
        new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Controller conflict detected"))
                .setMessage("Another Thor volume-control service is enabled. Both apps may respond to the same volume or controller input, which can make brightness and volume change together.\n\nRoot or Shizuku access is required for \"Suspend services during hold\". If you want Thor's Lightning to take priority while the modifier is held, enable \"Suspend services during hold\" and choose the detected service. You can dismiss this notice and change the option later.")
                .setPositiveButton("Got it", null)
                .show();
    }


    @Override
    protected void onDestroy() {
        BrightnessKeyService.captureListener = null;
        stopRootDirectionRecorder();
        super.onDestroy();
    }

    private void updateRepeatLabel(int delay) {
        repeatLabel.setText(delay + " ms");
    }

    private String appVersionName() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasRootAccess() {
        try {
            Process process = new ProcessBuilder("su", "-c", "id").start();
            int result = process.waitFor();
            return result == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateRootStatus(boolean enabled) {
        if (rootStatus == null) return;
        boolean shizuku = ShizukuSupport.available();
        boolean root = !shizuku && hasRootAccess();
        Button[] privilegeButtons = {checkRootButton, rootCheckPrimary};
        for (Button privilegeButton : privilegeButtons) {
            if (privilegeButton != null && !rootCheckRunning) {
                privilegeButton.setText(shizuku ? "Shizuku (ADB) - Enabled"
                        : root ? "Root access - Enabled" : "Privilege access - Needed");
                applyStatus(privilegeButton, root || shizuku);
            }
        }
        // Keep this explanatory text stable; the switch state itself shows
        // whether root directional input is enabled.
        rootStatus.setText("Detected: " + (shizuku ? ShizukuSupport.status() : root ? "Root" : "None"));
    }

    private String keyName(int code) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP) return "Vol +";
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) return "Vol \u2212";
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "")
                .replace("BUTTON_", "").replace('_', ' ');
    }

    private CharSequence mappingLabel(String title, int fallbackKey) {
        String assigned;
        if ("Modifier".equals(title) && prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0) != 0) {
            int source = prefs.getInt(Prefs.ROOT_MODIFIER_SOURCE, Prefs.AXIS_DPAD);
            assigned = sourceName(source) + " " + rootDirectionName(
                    prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0));
        } else if (!(prefs.getBoolean(Prefs.ROOT_AXES, false)
                && rootDirectionMapped(title))
                || (!"Brighter".equals(title) && !"Dimmer".equals(title))) {
            assigned = keyName(fallbackKey);
        } else {
            int source = prefs.getInt("Brighter".equals(title)
                    ? Prefs.ROOT_BRIGHT_SOURCE : Prefs.ROOT_DIM_SOURCE, Prefs.AXIS_DPAD);
            String control = sourceName(source);
            int direction = prefs.getInt("Brighter".equals(title)
                    ? Prefs.ROOT_BRIGHT_DIRECTION : Prefs.ROOT_DIM_DIRECTION, 0);
            assigned = control + " " + rootDirectionName(direction);
        }
        return styledLabel(title, assigned);
    }

    /** Binding 2's Brighter/Dimmer: the recorded direction, else the button. */
    private String bindingBAssigned(boolean bright) {
        int dir = prefs.getInt(bright ? Prefs.ROOT_B_BRIGHT_DIRECTION : Prefs.ROOT_B_DIM_DIRECTION, 0);
        if (prefs.getBoolean(Prefs.ROOT_AXES, false) && dir != 0) {
            int source = prefs.getInt(bright ? Prefs.ROOT_B_BRIGHT_SOURCE : Prefs.ROOT_B_DIM_SOURCE, Prefs.AXIS_DPAD);
            return sourceName(source) + " " + rootDirectionName(dir);
        }
        String pref = bright ? Prefs.B_UP_KEY : Prefs.B_DOWN_KEY;
        return keyName(prefs.getInt(pref, Prefs.defaultKey(pref)));
    }

    private CharSequence styledLabel(String title, String assigned) {
        String full = title + "\n" + assigned;
        SpannableString styled = new SpannableString(full);
        styled.setSpan(new ForegroundColorSpan(Palette.MAUVE),
                full.length() - assigned.length(), full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.RelativeSizeSpan(1.15f),
                full.length() - assigned.length(), full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(Palette.SUBTEXT0),
                0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.RelativeSizeSpan(0.8f),
                0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private boolean rootDirectionMapped(String title) {
        if ("Brighter".equals(title)) {
            return prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0) != 0;
        }
        if ("Dimmer".equals(title)) {
            return prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0) != 0;
        }
        return false;
    }

    private String rootDirectionName(int direction) {
        switch (direction) {
            case Prefs.ROOT_DIRECTION_UP: return "Up";
            case Prefs.ROOT_DIRECTION_DOWN: return "Down";
            case Prefs.ROOT_DIRECTION_LEFT: return "Left";
            case Prefs.ROOT_DIRECTION_RIGHT: return "Right";
            default: return "Not recorded";
        }
    }

    private String sourceName(int source) {
        return source == Prefs.AXIS_RIGHT_STICK ? "R-stick"
                : source == Prefs.AXIS_LEFT_STICK ? "L-stick" : "D-pad";
    }

    private void updateRootMappingLabels() {
        if (modifierMappingLabel != null) {
            modifierMappingLabel.setText(mappingLabel("Modifier",
                    prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1)));
        }
        if (brighterMappingLabel != null) {
            brighterMappingLabel.setText(mappingLabel("Brighter",
                    prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP)));
        }
        if (dimmerMappingLabel != null) {
            dimmerMappingLabel.setText(mappingLabel("Dimmer",
                    prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN)));
        }
        updateBindingSummaries();
        if (bModifierMappingLabel != null) {
            bModifierMappingLabel.setText(styledLabel("Modifier",
                    keyName(prefs.getInt(Prefs.B_MODIFIER, Prefs.defaultKey(Prefs.B_MODIFIER)))));
        }
        if (bBrighterMappingLabel != null) {
            bBrighterMappingLabel.setText(styledLabel("Brighter", bindingBAssigned(true)));
        }
        if (bDimmerMappingLabel != null) {
            bDimmerMappingLabel.setText(styledLabel("Dimmer", bindingBAssigned(false)));
        }
    }

    private void updateSafetyText() {
        if (safetyLabel == null) return;
        String modifier = keyName(prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1));
        String up = keyName(prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP));
        String down = keyName(prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN));
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)) {
            safetyLabel.setText("Root safety: press " + modifier + " + " + up + " + " + down
                    + " together to disable. These are the saved fallback buttons; stick axes cannot send opposing directions at the same time.");
        } else {
            safetyLabel.setText("Safety: press " + modifier + " + " + up + " + " + down
                    + " together to disable. Mapped buttons work normally without the modifier.");
        }
    }

    private void migrateUnsupportedMappings() {
        int up = prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP);
        int down = prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (isDpadKey(up)) {
            editor.putInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP);
            changed = true;
        }
        if (isDpadKey(down)) {
            editor.putInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN);
            changed = true;
        }
        if (changed) editor.apply();
    }

    private boolean isDpadKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private void updateStatusPill(boolean ready) {
        if (statusPill == null) return;
        statusPill.setText(ready ? "\u25CF  Ready" : "\u25CF  Setup needed");
        statusPill.setTextColor(ready ? Palette.GREEN : Palette.PEACH);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Palette.withAlpha(ready ? Palette.GREEN : Palette.PEACH, 0x30));
        pill.setCornerRadius(dp(CORNER));
        statusPill.setBackground(pill);
    }

    /**
     * Setup popup: required permissions, then optional root/Shizuku. Opens on
     * first launch (and whenever permissions are missing) and from the
     * header status pill.
     */
    private void showSetupDialog() {
        if (setupDialog != null && setupDialog.isShowing()) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(4));

        TextView stepOne = text("1  \u00B7  Permissions (required)", 15, true);
        stepOne.setTextColor(Palette.MAUVE);
        content.addView(stepOne, matchMargins(0, 0, 0, 2));
        TextView stepOneHint = text("Brightness control and key detection. Tap the button and follow "
                + "the prompts; press B to come back here.", 13, false);
        stepOneHint.setTextColor(Palette.SUBTEXT0);
        content.addView(stepOneHint, matchMargins(0, 0, 0, 6));
        if (setupPanel.getParent() != null) ((ViewGroup) setupPanel.getParent()).removeView(setupPanel);
        content.addView(setupPanel, matchMargins(0, 0, 0, 14));

        TextView stepTwo = text("2  \u00B7  Privileged input (optional)", 15, true);
        stepTwo.setTextColor(Palette.MAUVE);
        content.addView(stepTwo, matchMargins(0, 0, 0, 2));
        TextView stepTwoHint = text("Shizuku or root lets bindings use the D-pad and sticks, and "
                + "pause conflicting apps while a modifier is held. Skip it if you only use buttons.", 13, false);
        stepTwoHint.setTextColor(Palette.SUBTEXT0);
        content.addView(stepTwoHint, matchMargins(0, 0, 0, 6));
        Button privilege = button("Check privilege access");
        privilege.setHeight(dp(44));
        privilege.setOnClickListener(v -> checkRootAccess(privilege));
        updateRootCheckVisual(privilege);
        content.addView(privilege, matchMargins(0, 0, 0, 4));

        polish(content);
        setupDialog = new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Set up Thor's Lightning \u26A1"))
                .setView(content)
                .setPositiveButton("Done", (dialog, which) -> {
                    prefs.edit().putBoolean(Prefs.SETUP_GUIDE_SHOWN, true).apply();
                    new Handler(Looper.getMainLooper()).postDelayed(this::maybeShowConflictWarning, 250);
                })
                .create();
        setupDialog.show();
        if (setupDialog.getWindow() != null) {
            setupDialog.getWindow().setLayout(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        updateSetupButtons();
    }

    /** Section title with a small ⓘ that opens an explanation dialog. */
    private LinearLayout headerWithInfo(String title, String dialogTitle, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(section(title), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Bare icon, no button box; a ring still shows when focused by controller.
        TextView info = new TextView(this);
        info.setText("\u24D8");
        info.setTextSize(20);
        info.setTextColor(Palette.SUBTEXT0);
        info.setGravity(Gravity.CENTER);
        info.setFocusable(true);
        info.setClickable(true);
        info.setBackground(focusRing(CORNER));
        info.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(dialogTitle).setMessage(body)
                .setPositiveButton("Got it", null).show());
        row.addView(info, new LinearLayout.LayoutParams(dp(34), dp(34)));
        return row;
    }

    private LinearLayout stack(View... views) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (View view : views) column.addView(view, matchMargins(0, 0, 0, 0));
        return column;
    }

    /** e.g. "L1 + D-pad Up / Down  →  Top" */
    private void updateBindingSummaries() {
        if (bindingSummaryA != null) bindingSummaryA.setText(bindingSummary(false));
        if (bindingSummaryB != null) bindingSummaryB.setText(bindingSummary(true));
    }

    private String bindingSummary(boolean second) {
        String modifierPref = second ? Prefs.B_MODIFIER : Prefs.MODIFIER;
        String modifier = keyName(prefs.getInt(modifierPref, Prefs.defaultKey(modifierPref)));
        String bright = assignedName(second, true);
        String dim = assignedName(second, false);
        String keys;
        String brightSource = bright.contains(" ") ? bright.substring(0, bright.lastIndexOf(' ')) : "";
        String dimSource = dim.contains(" ") ? dim.substring(0, dim.lastIndexOf(' ')) : "";
        if (!brightSource.isEmpty() && brightSource.equals(dimSource)) {
            keys = brightSource + " " + bright.substring(bright.lastIndexOf(' ') + 1)
                    + " / " + dim.substring(dim.lastIndexOf(' ') + 1);
        } else {
            keys = bright + " / " + dim;
        }
        int target = prefs.getInt(second ? Prefs.B_TARGET : Prefs.TARGET,
                second ? Prefs.TARGET_BOTTOM : Prefs.TARGET_BOTH);
        String screen = target == Prefs.TARGET_TOP ? "Top" : target == Prefs.TARGET_BOTTOM ? "Bottom" : "Both screens";
        return modifier + " + " + keys + "  \u2192  " + screen;
    }

    /** Short name of a binding's Brighter/Dimmer: "D-pad Up" or "VOLUME UP". */
    private String assignedName(boolean second, boolean bright) {
        String dirKey = second ? (bright ? Prefs.ROOT_B_BRIGHT_DIRECTION : Prefs.ROOT_B_DIM_DIRECTION)
                : (bright ? Prefs.ROOT_BRIGHT_DIRECTION : Prefs.ROOT_DIM_DIRECTION);
        String srcKey = second ? (bright ? Prefs.ROOT_B_BRIGHT_SOURCE : Prefs.ROOT_B_DIM_SOURCE)
                : (bright ? Prefs.ROOT_BRIGHT_SOURCE : Prefs.ROOT_DIM_SOURCE);
        int direction = prefs.getInt(dirKey, 0);
        if (prefs.getBoolean(Prefs.ROOT_AXES, false) && direction != 0) {
            return sourceName(prefs.getInt(srcKey, Prefs.AXIS_DPAD)) + " " + rootDirectionName(direction);
        }
        String pref = second ? (bright ? Prefs.B_UP_KEY : Prefs.B_DOWN_KEY) : (bright ? Prefs.UP_KEY : Prefs.DOWN_KEY);
        return keyName(prefs.getInt(pref, Prefs.defaultKey(pref)));
    }

    private TextView section(String value) {
        TextView view = text(value, 17, true);
        view.setTextColor(Palette.MAUVE);
        view.setLetterSpacing(0.02f);
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Palette.TEXT);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    /** Flat rounded button on a surface layer (no Material shadow). */
    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(Palette.TEXT);
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(CORNER));
        button.setBackground(shape);
        button.setBackgroundTintList(ColorStateList.valueOf(Palette.surface1));
        return button;
    }

    /** Status button: soft green when ready, soft peach when something is needed. */
    private void applyStatus(Button button, boolean ok) {
        if (button == null) return;
        button.setBackgroundTintList(Palette.statusFill(ok));
        button.setTextColor(Palette.statusText(ok));
    }

    /** Outline drawn only while a view has controller/keyboard focus. */
    private android.graphics.drawable.Drawable focusRing(int radiusDp) {
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(radiusDp));
        ring.setStroke(dp(3), Palette.LAVENDER);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    /**
     * Final theming pass over the built screen: Macchiato tints for stock
     * widgets and a visible focus ring on everything the D-pad can reach.
     */
    private void polish(View view) {
        int[][] checkedStates = {{android.R.attr.state_checked}, {}};
        if (view instanceof Switch) {
            Switch toggle = (Switch) view;
            toggle.setTextColor(Palette.TEXT);
            toggle.setThumbTintList(new ColorStateList(checkedStates,
                    new int[]{Palette.MAUVE, Palette.OVERLAY1}));
            toggle.setTrackTintList(new ColorStateList(checkedStates,
                    new int[]{Palette.withAlpha(Palette.MAUVE, 0x80), Palette.surface1}));
        } else if (view instanceof CheckBox) {
            CheckBox box = (CheckBox) view;
            box.setTextColor(Palette.TEXT);
            box.setButtonTintList(new ColorStateList(checkedStates,
                    new int[]{Palette.MAUVE, Palette.OVERLAY1}));
        } else if (view instanceof SeekBar) {
            SeekBar bar = (SeekBar) view;
            bar.setProgressTintList(ColorStateList.valueOf(Palette.MAUVE));
            bar.setThumbTintList(ColorStateList.valueOf(Palette.MAUVE));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Palette.surface2));
        }
        if (view.isFocusable() && !(view instanceof ViewGroup) && view.getForeground() == null) {
            view.setForeground(focusRing(CORNER));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) polish(group.getChildAt(i));
        }
    }

    private TextView centeredDialogTitle(String value) {
        TextView title = text(value, 20, false);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(dp(24), dp(16), dp(24), dp(8));
        return title;
    }

    private void styleSetupButton(Button button) {
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(Color.WHITE);
        buttonBackground.setCornerRadius(dp(CORNER));
        button.setBackground(buttonBackground);
        button.setBackgroundTintList(ColorStateList.valueOf(Palette.surface1));
        // Keep the setup controls compact so the fixed three-column layout
        // clears the Thor's three-button navigation bar.
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
    }

    private RadioButton radio(String value, int target) {
        RadioButton radio = new RadioButton(this);
        radio.setId(View.generateViewId());
        radio.setText(value);
        radio.setTextSize(14);
        radio.setTag(target);
        return radio;
    }

    private ScrollView scrollCard(LinearLayout card) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(card.getBackground());
        scroll.setClipToOutline(true);
        card.setBackground(null);
        scroll.addView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(4), dp(14), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Palette.mantle);
        background.setCornerRadius(dp(CORNER));
        background.setStroke(dp(1), Palette.surface0);
        card.setBackground(background);
        return card;
    }

    private LinearLayout.LayoutParams weightedCardMargins(
            int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams matchMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int themeColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        if (value.resourceId != 0) {
            try {
                return getResources().getColor(value.resourceId, getTheme());
            } catch (Resources.NotFoundException ignored) {
                // Fall through to a directly resolved color.
            }
        }
        return value.data;
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}

