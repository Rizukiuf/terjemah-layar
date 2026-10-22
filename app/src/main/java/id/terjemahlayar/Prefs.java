package id.terjemahlayar;

import android.content.Context;
import android.content.SharedPreferences;

/** Pengaturan sederhana yang disimpan di HP. */
final class Prefs {
    static final String SCRIPT_LATIN = "latin";
    static final String SCRIPT_CHINESE = "chinese";
    static final String SCRIPT_JAPANESE = "japanese";
    static final String SCRIPT_KOREAN = "korean";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("terjemah_layar", Context.MODE_PRIVATE);
    }

    /** Bahasa tujuan terjemahan (kode ML Kit, mis. "id"). */
    static String target(Context c) { return sp(c).getString("target", "id"); }
    static void setTarget(Context c, String v) { sp(c).edit().putString("target", v).apply(); }

    /** Jenis huruf teks sumber yang dibaca OCR. */
    static String script(Context c) { return sp(c).getString("script", SCRIPT_LATIN); }
    static void setScript(Context c, String v) { sp(c).edit().putString("script", v).apply(); }

    static boolean bubbleEnabled(Context c) { return sp(c).getBoolean("bubble", true); }
    static void setBubbleEnabled(Context c, boolean v) { sp(c).edit().putBoolean("bubble", v).apply(); }

    static int bubbleX(Context c) { return sp(c).getInt("bx", -1); }
    static int bubbleY(Context c) { return sp(c).getInt("by", -1); }
    static void setBubblePos(Context c, int x, int y) {
        sp(c).edit().putInt("bx", x).putInt("by", y).apply();
    }
}
