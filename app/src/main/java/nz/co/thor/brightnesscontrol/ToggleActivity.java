package nz.co.thor.brightnesscontrol;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible target of the launcher shortcut: flips Enabled and closes. */
public class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean enabled = Prefs.toggleEnabled(this);
        Toast.makeText(this, enabled ? "Thor's Lightning on" : "Thor's Lightning off",
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
