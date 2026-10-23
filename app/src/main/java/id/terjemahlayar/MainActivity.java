package id.terjemahlayar;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.List;

/** Layar pengaturan & panduan singkat. */
public class MainActivity extends Activity {

    private TextView status;
    private Button enableButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("Terjemah Layar", 26, true);
        root.addView(title);
        root.addView(text("Satu ketukan untuk menerjemahkan apa pun yang tampil di layar.", 15, false));

        // ---- Langkah 1: aktifkan layanan
        root.addView(header("1. Aktifkan layanan"));
        status = text("", 15, true);
        root.addView(status);
        enableButton = new Button(this);
        enableButton.setText("Buka pengaturan Aksesibilitas");
        enableButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enableButton);
        root.addView(note("Di halaman itu, cari \"Terjemah Layar\" (bisa ada di bagian \"Aplikasi terdownload\" "
                + "atau \"Layanan terinstal\") lalu nyalakan.\n\n"
                + "Jika tombolnya abu-abu / muncul tulisan \"Setelan terbatas\": buka Info Aplikasi di bawah, "
                + "ketuk ⋮ (pojok kanan atas) → \"Izinkan setelan terbatas\", lalu coba lagi."));
        Button appInfo = new Button(this);
        appInfo.setText("Buka Info Aplikasi");
        appInfo.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        root.addView(appInfo);

        // ---- Langkah 2: bahasa
        root.addView(header("2. Bahasa"));
        root.addView(note("Dari (bahasa teks di layar):"));
        root.addView(languageSpinner(Langs.sourceOptions(), Prefs.source(this),
                code -> Prefs.setSource(this, code)));
        root.addView(note("Ke (bahasa terjemahan):"));
        root.addView(languageSpinner(Langs.targetOptions(), Prefs.target(this),
                code -> Prefs.setTarget(this, code)));
        root.addView(note("\"Otomatis\" mengenali sendiri teks Inggris, Jepang, Korea, Mandarin, dan bahasa berhuruf Latin lain. "
                + "Bahasa juga bisa diganti langsung di layar hasil terjemahan lewat tombol di bagian atas."));

        // ---- Langkah 4: tombol & pintasan
        root.addView(header("3. Cara memicu terjemahan"));
        CheckBox bubbleBox = new CheckBox(this);
        bubbleBox.setText("Tampilkan tombol mengambang 文A");
        bubbleBox.setChecked(Prefs.bubbleEnabled(this));
        bubbleBox.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setBubbleEnabled(this, checked);
            ScreenTranslateService s = ScreenTranslateService.get();
            if (s != null) s.applyPrefs();
        });
        root.addView(bubbleBox);

        if (Build.VERSION.SDK_INT >= 33) {
            Button addTile = new Button(this);
            addTile.setText("Tambahkan tile ke Pengaturan Cepat");
            addTile.setOnClickListener(v -> requestAddTile());
            root.addView(addTile);
        }
        root.addView(note("• Ketuk tombol 文A → layar langsung diterjemahkan.\n"
                + "• Geser tombol untuk memindahkannya. Tahan tombol untuk membuka halaman ini.\n"
                + "• Pada hasil terjemahan: ketuk di mana saja untuk menutup, tahan untuk mengintip teks asli, "
                + "ketuk tombol bahasa di atas untuk mengganti bahasa.\n"
                + "• \"Daftar\" menampilkan semua teks asli + terjemahan, \"Salin\" menyalin terjemahan, "
                + "\"Lens\" membuka gambar layar di Google untuk bertanya soal isinya.\n"
                + "• Alternatif tanpa tombol mengambang: tile \"Terjemah Layar\" di panel notifikasi."));

        // ---- Unduh paket bahasa
        root.addView(header("4. Siapkan paket bahasa (opsional)"));
        root.addView(note("Saat pertama kali menerjemahkan dari suatu bahasa, aplikasi mengunduh paket bahasa (±30 MB). "
                + "Setelah itu terjemahan bekerja tanpa internet. Anda bisa mengunduhnya sekarang lewat Wi-Fi:"));
        LinearLayout dlRow = new LinearLayout(this);
        dlRow.setOrientation(LinearLayout.HORIZONTAL);
        dlRow.addView(downloadButton("Inggris", TranslateLanguage.ENGLISH), weight());
        dlRow.addView(downloadButton("Mandarin", TranslateLanguage.CHINESE), weight());
        root.addView(dlRow);
        LinearLayout dlRow2 = new LinearLayout(this);
        dlRow2.setOrientation(LinearLayout.HORIZONTAL);
        dlRow2.addView(downloadButton("Jepang", TranslateLanguage.JAPANESE), weight());
        dlRow2.addView(downloadButton("Korea", TranslateLanguage.KOREAN), weight());
        root.addView(dlRow2);

        root.addView(header("Privasi"));
        root.addView(note("Gambar layar dibaca dan diterjemahkan di dalam HP Anda dan tidak dikirim ke server mana pun. "
                + "Pengecualian: tombol \"Lens\" mengirim gambar layar ke aplikasi Google atas pilihan Anda sendiri. "
                + "Aplikasi yang melarang tangkapan layar (mis. aplikasi bank) tidak bisa diterjemahkan."));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = ScreenTranslateService.get() != null;
        status.setText(on ? "Status: AKTIF ✓" : "Status: belum aktif");
        status.setTextColor(on ? 0xFF188038 : 0xFFD93025);
        enableButton.setText(on ? "Pengaturan Aksesibilitas" : "Buka pengaturan Aksesibilitas");
    }

    private void requestAddTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(
                new ComponentName(this, TranslateTileService.class),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_translate),
                getMainExecutor(),
                result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Toast.makeText(this, "Tile sudah ada di Pengaturan Cepat", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private Button downloadButton(String label, String src) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> {
            String target = Prefs.target(this);
            if (src.equals(target)) {
                Toast.makeText(this, "Itu sama dengan bahasa tujuan.", Toast.LENGTH_SHORT).show();
                return;
            }
            b.setEnabled(false);
            b.setText(label + " …");
            Translator t = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(src).setTargetLanguage(target).build());
            t.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(x -> {
                        b.setText(label + " ✓");
                        t.close();
                    })
                    .addOnFailureListener(e -> {
                        b.setEnabled(true);
                        b.setText(label);
                        Toast.makeText(this, "Gagal mengunduh: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        t.close();
                    });
        });
        return b;
    }

    private interface OnPick { void pick(String code); }

    private Spinner languageSpinner(List<String> codes, String current, OnPick onPick) {
        Spinner sp = new Spinner(this);
        List<String> names = new ArrayList<>();
        for (String c : codes) names.add(Langs.AUTO.equals(c) ? "Otomatis (deteksi)" : Langs.displayName(c));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        int sel = codes.indexOf(current);
        if (sel >= 0) sp.setSelection(sel);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPick.pick(codes.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        return sp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView header(String s) {
        TextView t = text(s, 18, true);
        t.setPadding(0, dp(24), 0, dp(6));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 14, false);
        t.setAlpha(0.8f);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(isDark() ? Color.WHITE : 0xFF202124);
        return t;
    }

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
