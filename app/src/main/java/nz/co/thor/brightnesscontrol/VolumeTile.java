package nz.co.thor.brightnesscontrol;

/** Quick Settings tile: volume control on/off. */
public class VolumeTile extends ToggleTile {
    @Override String key() { return Prefs.VOL_ENABLED; }
    @Override String label() { return "Volume"; }
    @Override int icon() { return R.drawable.ic_speaker; }
}
