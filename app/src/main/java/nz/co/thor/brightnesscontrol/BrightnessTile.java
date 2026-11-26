package nz.co.thor.brightnesscontrol;

/** Quick Settings tile: brightness control on/off. */
public class BrightnessTile extends ToggleTile {
    @Override String key() { return Prefs.ENABLED; }
    @Override String label() { return "Brightness"; }
    @Override int icon() { return R.drawable.ic_sun; }
}
