package nz.co.thor.brightnesscontrol;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that turns the app on or off. */
public class ToggleTileService extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Prefs.toggleEnabled(this);
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = Prefs.get(this).getBoolean(Prefs.ENABLED, true);
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_bolt));
        tile.setLabel(getString(R.string.tile_label));
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.setSubtitle(enabled ? "Brightness on" : "Brightness off");
        tile.updateTile();
    }
}
