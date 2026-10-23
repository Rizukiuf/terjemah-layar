package id.terjemahlayar;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Pembaca teks (OCR). Pada mode otomatis, pembaca Latin, Korea, dan Jepang dijalankan bersamaan,
 * lalu dipilih yang paling cocok dengan isi layar (Mandarin dicoba bila hanya ada huruf Han).
 */
final class OcrEngine {

    interface Callback {
        void onResult(Text text, String script);
        void onError(Exception e);
    }

    private final Map<String, TextRecognizer> recognizers = new HashMap<>();

    TextRecognizer get(String script) {
        TextRecognizer r = recognizers.get(script);
        if (r != null) return r;
        switch (script) {
            case Langs.SCRIPT_CHINESE:
                r = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                break;
            case Langs.SCRIPT_JAPANESE:
                r = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
                break;
            case Langs.SCRIPT_KOREAN:
                r = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                break;
            default:
                r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        recognizers.put(script, r);
        return r;
    }

    /** Muat model lebih awal agar terjemahan pertama tidak lambat. */
    void warmUp() {
        Bitmap tiny = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        InputImage img = InputImage.fromBitmap(tiny, 0);
        for (String s : new String[]{Langs.SCRIPT_LATIN, Langs.SCRIPT_JAPANESE, Langs.SCRIPT_KOREAN}) {
            get(s).process(img);
        }
    }

    void recognize(Bitmap bmp, String source, Callback cb) {
        final InputImage img = InputImage.fromBitmap(bmp, 0);
        if (!Langs.AUTO.equals(source)) {
            final String script = Langs.scriptFor(source);
            get(script).process(img)
                    .addOnSuccessListener(t -> cb.onResult(t, script))
                    .addOnFailureListener(cb::onError);
            return;
        }
        final String[] scripts = {Langs.SCRIPT_LATIN, Langs.SCRIPT_KOREAN, Langs.SCRIPT_JAPANESE};
        final Text[] results = new Text[scripts.length];
        final Exception[] lastError = {null};
        final int[] pending = {scripts.length};
        for (int i = 0; i < scripts.length; i++) {
            final int idx = i;
            Task<Text> task = get(scripts[i]).process(img);
            task.addOnCompleteListener(t -> {
                if (t.isSuccessful()) results[idx] = t.getResult();
                else lastError[0] = t.getException();
                if (--pending[0] == 0) decide(img, results, lastError[0], cb);
            });
        }
    }

    private void decide(InputImage img, Text[] r, Exception err, Callback cb) {
        Text latin = r[0], korean = r[1], japanese = r[2];
        int hangul = korean != null ? Langs.count(korean.getText())[0] : 0;
        int[] j = japanese != null ? Langs.count(japanese.getText()) : new int[4];
        int kana = j[1], han = j[2];

        if (korean != null && hangul >= 3 && hangul >= kana) {
            cb.onResult(korean, Langs.SCRIPT_KOREAN);
        } else if (japanese != null && kana >= 2) {
            cb.onResult(japanese, Langs.SCRIPT_JAPANESE);
        } else if (japanese != null && han >= 3) {
            // Hanya huruf Han tanpa kana → kemungkinan besar Mandarin.
            get(Langs.SCRIPT_CHINESE).process(img)
                    .addOnSuccessListener(t -> cb.onResult(t, Langs.SCRIPT_CHINESE))
                    .addOnFailureListener(e -> cb.onResult(japanese, Langs.SCRIPT_JAPANESE));
        } else if (latin != null) {
            cb.onResult(latin, Langs.SCRIPT_LATIN);
        } else if (korean != null) {
            cb.onResult(korean, Langs.SCRIPT_KOREAN);
        } else if (japanese != null) {
            cb.onResult(japanese, Langs.SCRIPT_JAPANESE);
        } else {
            cb.onError(err != null ? err : new Exception("OCR gagal"));
        }
    }

    void close() {
        for (TextRecognizer r : recognizers.values()) r.close();
        recognizers.clear();
    }
}
