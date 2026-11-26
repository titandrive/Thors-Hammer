package nz.co.thor.brightnesscontrol;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that turns one of the app's controls on or off. */
abstract class ToggleTile extends TileService {
    abstract String key();
    abstract String label();
    abstract int icon();

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Prefs.toggle(this, key());
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = Prefs.get(this).getBoolean(key(), Prefs.ENABLED.equals(key()));
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this, icon()));
        tile.setLabel(label());
        tile.setSubtitle(on ? "On" : "Off");
        tile.updateTile();
    }
}
