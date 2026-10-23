package id.terjemahlayar;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import id.terjemahlayar.LayoutAnalyzer.Group;

/**
 * Alur lengkap: baca teks (OCR) → susun jadi kalimat → deteksi bahasa → terjemahkan → hapus teks asli.
 * Semuanya berjalan di dalam HP; internet hanya dipakai sekali untuk mengunduh paket bahasa.
 * Semua callback dipanggil di thread utama.
 */
final class TranslatePipeline {

    interface Callback {
        void onStatus(String message);
        void onDone(Result result);
        void onError(String message);
    }

    static final class Result {
        final List<Group> groups;
        final String detected;

        Result(List<Group> groups, String detected) {
            this.groups = groups;
            this.detected = detected;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OcrEngine ocr = new OcrEngine();
    private final LanguageIdentifier languageId = LanguageIdentification.getClient();
    private final Map<String, Translator> translators = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation;

    // Hasil baca layar terakhir — dipakai ulang saat pengguna mengganti bahasa.
    private Bitmap cacheBmp;
    private String cacheKey, cacheScript;
    private List<Group> cacheGroups;

    void warmUp() { ocr.warmUp(); }

    void run(Bitmap bmp, String source, String target, Callback cb) {
        final int gen = ++generation;
        final String key = Langs.AUTO.equals(source) ? Langs.AUTO : Langs.scriptFor(source);
        if (bmp == cacheBmp && key.equals(cacheKey) && cacheGroups != null) {
            translate(gen, bmp, cacheGroups, cacheScript, source, target, cb);
            return;
        }
        ocr.recognize(bmp, source, new OcrEngine.Callback() {
            @Override
            public void onResult(com.google.mlkit.vision.text.Text text, String script) {
                if (gen != generation) return;
                List<Group> groups = LayoutAnalyzer.analyze(text, script);
                Runnable next = () -> {
                    cacheBmp = bmp;
                    cacheKey = key;
                    cacheScript = script;
                    cacheGroups = groups;
                    translate(gen, bmp, groups, script, source, target, cb);
                };
                if (Langs.SCRIPT_JAPANESE.equals(script) || Langs.SCRIPT_CHINESE.equals(script)) {
                    reflowVertical(gen, bmp, groups, script, next);
                } else {
                    next.run();
                }
            }

            @Override
            public void onError(Exception e) {
                if (gen == generation) cb.onError("Gagal membaca teks: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------ teks vertikal

    private void reflowVertical(int gen, Bitmap bmp, List<Group> groups, String script, Runnable then) {
        // Kelompokkan teks Jepang/Mandarin yang berdekatan (satu gelembung / satu blok).
        List<Group> cjk = new ArrayList<>();
        for (Group g : groups) if (g.isCjk()) cjk.add(g);
        if (cjk.isEmpty()) {
            then.run();
            return;
        }
        int n = cjk.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Group a = cjk.get(i), b = cjk.get(j);
                float thr = 0.5f * Math.max(a.thickness, b.thickness);
                if (LayoutAnalyzer.distance(a.bounds, b.bounds) <= thr) {
                    int ra = root(parent, i), rb = root(parent, j);
                    if (ra != rb) parent[rb] = ra;
                }
            }
        }
        Map<Integer, List<Group>> clusters = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = root(parent, i);
            List<Group> l = clusters.get(r);
            if (l == null) { l = new ArrayList<>(); clusters.put(r, l); }
            l.add(cjk.get(i));
        }
        final List<List<Group>> list = new ArrayList<>(clusters.values());

        worker.execute(() -> {
            List<VerticalReflow.Job> jobs = new ArrayList<>();
            for (List<Group> c : list) {
                Rect bounds = new Rect();
                boolean force = false;
                List<Float> th = new ArrayList<>();
                for (Group g : c) {
                    bounds.union(g.bounds);
                    force |= g.vertical;
                    th.add(g.thickness);
                }
                Collections.sort(th);
                try {
                    VerticalReflow.Job job = VerticalReflow.prepare(bmp, c, bounds, th.get(th.size() / 2), force);
                    if (job != null) jobs.add(job);
                } catch (RuntimeException ignored) {
                    // biarkan hasil OCR biasa
                }
            }
            main.post(() -> {
                if (gen != generation) return;
                if (jobs.isEmpty()) {
                    then.run();
                    return;
                }
                final String[] texts = new String[jobs.size()];
                final int[] pending = {jobs.size()};
                for (int i = 0; i < jobs.size(); i++) {
                    final int idx = i;
                    final VerticalReflow.Job job = jobs.get(i);
                    ocr.get(script).process(InputImage.fromBitmap(job.tiled, 0)).addOnCompleteListener(t -> {
                        if (t.isSuccessful()) {
                            String s = job.parse(t.getResult());
                            int[] c = Langs.count(s);
                            if (c[1] + c[2] > 0) texts[idx] = s;
                        }
                        if (--pending[0] == 0) {
                            if (gen != generation) return;
                            for (int k = 0; k < jobs.size(); k++) {
                                if (texts[k] == null) continue;
                                VerticalReflow.Job jb = jobs.get(k);
                                Group g = new Group();
                                for (Rect col : jb.columns) g.lines.add(new LayoutAnalyzer.Line(col, "", -1, true));
                                g.extraErase.addAll(jb.ruby);
                                g.text = texts[k];
                                g.vertical = true;
                                g.centered = true;
                                g.finish();
                                groups.removeAll(jb.source);
                                groups.add(g);
                            }
                            then.run();
                        }
                    });
                }
            });
        });
    }

    private static int root(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    // ------------------------------------------------------------ bahasa

    private void translate(int gen, Bitmap bmp, List<Group> groups, String script,
                           String source, String target, Callback cb) {
        List<Group> mainGroups = new ArrayList<>();
        for (Group g : groups) {
            g.lang = null;
            g.translated = null;
            if (!g.ruby) mainGroups.add(g);
        }
        if (mainGroups.isEmpty()) {
            cb.onError("Tidak ada teks yang terdeteksi di layar.");
            return;
        }
        boolean auto = Langs.AUTO.equals(source);
        if (!auto) {
            for (Group g : mainGroups) g.lang = source;
            translateAll(gen, bmp, groups, mainGroups, target, true, cb);
            return;
        }

        boolean screenKana = Langs.SCRIPT_JAPANESE.equals(script);
        for (Group g : mainGroups) if (g.kana > 0) screenKana = true;
        final List<Group> needId = new ArrayList<>();
        for (Group g : mainGroups) {
            if (g.hangul > 0 && g.hangul >= g.kana + g.han) g.lang = TranslateLanguage.KOREAN;
            else if (g.kana > 0) g.lang = TranslateLanguage.JAPANESE;
            else if (g.han > 0 && g.han >= g.latin) g.lang = screenKana ? TranslateLanguage.JAPANESE : TranslateLanguage.CHINESE;
            else needId.add(g);
        }
        if (needId.isEmpty()) {
            translateAll(gen, bmp, groups, mainGroups, target, false, cb);
            return;
        }
        final String[] tags = new String[needId.size()];
        final int[] pending = {needId.size()};
        for (int i = 0; i < needId.size(); i++) {
            final int idx = i;
            languageId.identifyLanguage(needId.get(i).text).addOnCompleteListener(t -> {
                tags[idx] = t.isSuccessful() ? t.getResult() : LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG;
                if (--pending[0] > 0) return;
                if (gen != generation) return;
                // Bahasa dominan dipakai untuk potongan pendek yang sulit dideteksi.
                Map<String, Integer> weight = new HashMap<>();
                for (int k = 0; k < needId.size(); k++) {
                    String m = tags[k] == null ? null : TranslateLanguage.fromLanguageTag(tags[k]);
                    if (m == null) continue;
                    Integer w = weight.get(m);
                    weight.put(m, (w == null ? 0 : w) + needId.get(k).text.length());
                }
                String dominant = null;
                int best = -1;
                for (Map.Entry<String, Integer> e : weight.entrySet()) {
                    if (e.getValue() > best) { best = e.getValue(); dominant = e.getKey(); }
                }
                String fallback = dominant != null ? dominant : TranslateLanguage.ENGLISH;
                for (int k = 0; k < needId.size(); k++) {
                    Group g = needId.get(k);
                    String m = tags[k] == null ? null : TranslateLanguage.fromLanguageTag(tags[k]);
                    boolean confident = m != null && g.text.length() >= 12;
                    g.lang = confident ? m : (m != null && m.equals(target) ? target : fallback);
                }
                translateAll(gen, bmp, groups, mainGroups, target, false, cb);
            });
        }
    }

    private void translateAll(int gen, Bitmap bmp, List<Group> all, List<Group> mainGroups,
                              String target, boolean manual, Callback cb) {
        Map<String, List<Group>> bySource = new HashMap<>();
        for (Group g : mainGroups) {
            if (g.lang == null || g.lang.equals(target)) continue;
            List<Group> l = bySource.get(g.lang);
            if (l == null) { l = new ArrayList<>(); bySource.put(g.lang, l); }
            l.add(g);
        }
        if (bySource.isEmpty()) {
            cb.onError("Teks di layar sudah dalam bahasa " + Langs.displayName(target)
                    + ". Ganti bahasa tujuan bila perlu.");
            return;
        }
        int total = 0;
        for (List<Group> l : bySource.values()) total += l.size();
        final int[] pending = {total};
        final String[] lastError = {null};
        final Runnable finish = () -> {
            if (gen != generation) return;
            render(gen, bmp, all, mainGroups, manual, lastError[0], cb);
        };
        for (Map.Entry<String, List<Group>> e : bySource.entrySet()) {
            final String src = e.getKey();
            final List<Group> list = e.getValue();
            final Translator tr = translatorFor(src, target);
            final boolean[] ready = {false};
            main.postDelayed(() -> {
                if (!ready[0] && gen == generation) {
                    cb.onStatus("Mengunduh paket bahasa " + Langs.displayName(src) + " → "
                            + Langs.displayName(target) + " (sekali saja, ±30 MB)…");
                }
            }, 1500);
            tr.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(v -> {
                        ready[0] = true;
                        for (Group g : list) {
                            tr.translate(g.text).addOnCompleteListener(t -> {
                                if (t.isSuccessful()) g.translated = t.getResult();
                                else if (t.getException() != null) lastError[0] = t.getException().getMessage();
                                if (--pending[0] == 0) finish.run();
                            });
                        }
                    })
                    .addOnFailureListener(ex -> {
                        ready[0] = true;
                        lastError[0] = "Paket bahasa " + Langs.displayName(src) + " gagal diunduh.";
                        pending[0] -= list.size();
                        if (pending[0] == 0) finish.run();
                    });
        }
    }

    private void render(int gen, Bitmap bmp, List<Group> all, List<Group> mainGroups, boolean manual,
                        String lastError, Callback cb) {
        final List<Group> shown = new ArrayList<>();
        Map<String, Integer> weight = new HashMap<>();
        boolean anyJa = false;
        for (Group g : mainGroups) {
            if (g.translated == null || g.translated.trim().isEmpty()) continue;
            shown.add(g);
            Integer w = weight.get(g.lang);
            weight.put(g.lang, (w == null ? 0 : w) + g.text.length());
            if (TranslateLanguage.JAPANESE.equals(g.lang)) anyJa = true;
        }
        if (shown.isEmpty()) {
            cb.onError("Gagal menerjemahkan. " + (lastError != null ? lastError + " " : "")
                    + "Pastikan internet aktif saat pertama kali memakai bahasa ini.");
            return;
        }
        if (anyJa) for (Group g : all) if (g.ruby) shown.add(g);
        String dom = null;
        int best = -1;
        for (Map.Entry<String, Integer> e : weight.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); dom = e.getKey(); }
        }
        final String detected = dom;
        worker.execute(() -> {
            for (Group g : shown) {
                try { Eraser.erase(bmp, g); } catch (RuntimeException ignored) { }
            }
            main.post(() -> {
                if (gen == generation) cb.onDone(new Result(shown, detected));
            });
        });
    }

    private Translator translatorFor(String src, String target) {
        String key = src + ">" + target;
        Translator t = translators.get(key);
        if (t == null) {
            t = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(target)
                    .build());
            translators.put(key, t);
        }
        return t;
    }

    void close() {
        generation++;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        for (Translator t : translators.values()) t.close();
        translators.clear();
        ocr.close();
        languageId.close();
    }
}
