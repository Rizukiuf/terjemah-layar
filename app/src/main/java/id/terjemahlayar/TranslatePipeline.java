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
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Baca teks dari gambar layar (OCR), deteksi bahasanya, lalu terjemahkan.
 * Semuanya berjalan di dalam HP; internet hanya dipakai sekali untuk mengunduh paket bahasa.
 * Semua callback dipanggil di thread utama.
 */
final class TranslatePipeline {

    interface Callback {
        void onStatus(String message);
        void onDone(List<Item> items);
        void onError(String message);
    }

    static final class Item {
        final Rect box;
        final String original;
        final int lineCount;
        String lang;
        String translated;

        Item(Rect box, String original, int lineCount) {
            this.box = box;
            this.original = original;
            this.lineCount = lineCount;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Translator> translators = new HashMap<>();
    private final LanguageIdentifier languageId = LanguageIdentification.getClient();
    private TextRecognizer recognizer;
    private String recognizerScript;

    void run(Bitmap bitmap, String script, String target, Callback cb) {
        TextRecognizer r = recognizerFor(script);
        r.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> onText(text, script, target, cb))
                .addOnFailureListener(e -> cb.onError("Gagal membaca teks: " + e.getMessage()));
    }

    private TextRecognizer recognizerFor(String script) {
        if (recognizer != null && script.equals(recognizerScript)) return recognizer;
        if (recognizer != null) recognizer.close();
        switch (script) {
            case Prefs.SCRIPT_CHINESE:
                recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                break;
            case Prefs.SCRIPT_JAPANESE:
                recognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
                break;
            case Prefs.SCRIPT_KOREAN:
                recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                break;
            default:
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        recognizerScript = script;
        return recognizer;
    }

    private static boolean isCjk(String script) {
        return Prefs.SCRIPT_CHINESE.equals(script) || Prefs.SCRIPT_JAPANESE.equals(script);
    }

    private static String scriptDefaultLanguage(String script) {
        switch (script) {
            case Prefs.SCRIPT_CHINESE: return TranslateLanguage.CHINESE;
            case Prefs.SCRIPT_JAPANESE: return TranslateLanguage.JAPANESE;
            case Prefs.SCRIPT_KOREAN: return TranslateLanguage.KOREAN;
            default: return TranslateLanguage.ENGLISH;
        }
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private void onText(Text text, String script, String target, Callback cb) {
        boolean cjk = isCjk(script);
        List<Item> items = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box == null) continue;
            StringBuilder sb = new StringBuilder();
            int lines = 0;
            for (Text.Line line : block.getLines()) {
                String t = line.getText().trim();
                if (t.isEmpty()) continue;
                lines++;
                if (sb.length() > 0 && !cjk) {
                    // gabungkan kata yang terpotong tanda hubung di akhir baris
                    if (sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
                    else sb.append(' ');
                }
                sb.append(t);
            }
            String joined = sb.toString().trim();
            if (joined.isEmpty() || !hasLetter(joined)) continue;
            items.add(new Item(box, joined, Math.max(1, lines)));
        }
        if (items.isEmpty()) {
            cb.onError("Tidak ada teks yang terdeteksi di layar.");
            return;
        }
        cb.onStatus("Menerjemahkan " + items.size() + " bagian teks…");

        final int[] pending = {items.size()};
        for (Item item : items) {
            languageId.identifyLanguage(item.original).addOnCompleteListener(task -> {
                item.lang = task.isSuccessful() ? task.getResult() : LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG;
                if (--pending[0] == 0) translateAll(items, script, target, cb);
            });
        }
    }

    private void translateAll(List<Item> items, String script, String target, Callback cb) {
        // Bahasa dominan di layar dipakai untuk potongan teks yang terlalu pendek untuk dideteksi.
        Map<String, Integer> weight = new HashMap<>();
        for (Item it : items) {
            String mapped = it.lang == null ? null : TranslateLanguage.fromLanguageTag(it.lang);
            if (mapped == null) continue;
            Integer w = weight.get(mapped);
            weight.put(mapped, (w == null ? 0 : w) + it.original.length());
        }
        String dominant = null;
        int best = -1;
        for (Map.Entry<String, Integer> e : weight.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); dominant = e.getKey(); }
        }
        // Jika yang dominan sudah bahasa tujuan, potongan pendek dianggap bahasa sesuai jenis huruf.
        String fallback = (dominant == null || dominant.equals(target)) ? scriptDefaultLanguage(script) : dominant;

        Map<String, List<Item>> bySource = new HashMap<>();
        for (Item it : items) {
            String mapped = it.lang == null ? null : TranslateLanguage.fromLanguageTag(it.lang);
            boolean confident = mapped != null && it.original.length() >= 12;
            String src = confident ? mapped : (mapped != null && mapped.equals(target) ? target : fallback);
            it.lang = src;
            if (src.equals(target)) continue; // sudah dalam bahasa tujuan → biarkan teks asli terlihat
            List<Item> list = bySource.get(src);
            if (list == null) { list = new ArrayList<>(); bySource.put(src, list); }
            list.add(it);
        }

        if (bySource.isEmpty()) {
            cb.onError("Teks di layar sepertinya sudah dalam bahasa tujuan.");
            return;
        }

        int total = 0;
        for (List<Item> l : bySource.values()) total += l.size();
        final int[] pending = {total};
        final String[] lastError = {null};
        final Runnable finish = () -> {
            List<Item> done = new ArrayList<>();
            for (Item it : items) if (it.translated != null) done.add(it);
            if (done.isEmpty()) {
                cb.onError("Gagal menerjemahkan. " + (lastError[0] != null ? lastError[0] + " " : "")
                        + "Pastikan internet aktif saat pertama kali memakai bahasa ini (untuk mengunduh paket bahasa).");
            } else {
                cb.onDone(done);
            }
        };

        for (Map.Entry<String, List<Item>> e : bySource.entrySet()) {
            final String src = e.getKey();
            final List<Item> list = e.getValue();
            final Translator tr = translatorFor(src, target);
            final boolean[] ready = {false};
            // Jika model belum siap setelah 1,5 detik, berarti sedang diunduh — beri tahu pengguna.
            main.postDelayed(() -> {
                if (!ready[0]) cb.onStatus("Mengunduh paket bahasa " + displayName(src) + " → "
                        + displayName(target) + " (sekali saja, ±30 MB)…");
            }, 1500);
            tr.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(v -> {
                        ready[0] = true;
                        for (Item it : list) {
                            tr.translate(it.original).addOnCompleteListener(t -> {
                                if (t.isSuccessful()) it.translated = t.getResult();
                                else if (t.getException() != null) lastError[0] = t.getException().getMessage();
                                if (--pending[0] == 0) finish.run();
                            });
                        }
                    })
                    .addOnFailureListener(ex -> {
                        ready[0] = true;
                        lastError[0] = "Paket bahasa " + displayName(src) + " gagal diunduh.";
                        pending[0] -= list.size();
                        if (pending[0] == 0) finish.run();
                    });
        }
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

    static String displayName(String code) {
        Locale l = Locale.forLanguageTag(code);
        String name = l.getDisplayLanguage(Locale.forLanguageTag("id"));
        if (name == null || name.isEmpty()) return code;
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    void close() {
        main.removeCallbacksAndMessages(null);
        for (Translator t : translators.values()) t.close();
        translators.clear();
        if (recognizer != null) recognizer.close();
        recognizer = null;
        languageId.close();
    }
}
