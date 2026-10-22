package id.terjemahlayar;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tile di Pengaturan Cepat (tarik panel notifikasi ke bawah) untuk menerjemahkan layar. */
public class TranslateTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = ScreenTranslateService.get() != null;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(on ? "Ketuk untuk terjemahkan" : "Belum diaktifkan");
        tile.updateTile();
    }

    @Override
    public void onClick() {
        ScreenTranslateService svc = ScreenTranslateService.get();
        if (svc != null) {
            svc.translateFromTile();
            return;
        }
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapseLegacy(i);
        }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseLegacy(Intent i) {
        startActivityAndCollapse(i);
    }
}
