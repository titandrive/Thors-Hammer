package nz.co.thor.brightnesscontrol;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible target of the launcher shortcuts: flips a control and closes. */
public class ToggleActivity extends Activity {
    static final String ACTION_BRIGHTNESS = "nz.co.thor.brightnesscontrol.TOGGLE_BRIGHTNESS";
    static final String ACTION_VOLUME = "nz.co.thor.brightnesscontrol.TOGGLE_VOLUME";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean volume = ACTION_VOLUME.equals(getIntent().getAction());
        boolean on = Prefs.toggle(this, volume ? Prefs.VOL_ENABLED : Prefs.ENABLED);
        Toast.makeText(this, (volume ? "Volume control " : "Brightness control ")
                + (on ? "on" : "off"), Toast.LENGTH_SHORT).show();
        finish();
    }
}
