package id.terjemahlayar;

import com.google.mlkit.nl.translate.TranslateLanguage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Daftar bahasa, nama tampilan, dan pengenal jenis huruf. */
final class Langs {
    static final String AUTO = "auto";

    static final String SCRIPT_LATIN = "latin";
    static final String SCRIPT_CHINESE = "chinese";
    static final String SCRIPT_JAPANESE = "japanese";
    static final String SCRIPT_KOREAN = "korean";

    /** Bahasa yang hurufnya tidak bisa dibaca OCR di aplikasi ini (Arab, Kiril, Thai, India, dll.). */
    private static final Set<String> UNREADABLE = new HashSet<>(Arrays.asList(
            "ar", "be", "bg", "bn", "el", "fa", "gu", "he", "hi", "ka", "kn", "mk", "mr",
            "ru", "ta", "te", "th", "uk", "ur"));

    private static final String[] COMMON_SOURCE = {"en", "ja", "ko", "zh"};
    private static final String[] COMMON_TARGET = {"id", "en", "ja", "ko", "zh", "ms"};

    private Langs() {}

    static String displayName(String code) {
        if (AUTO.equals(code)) return "Otomatis";
        if ("zh".equals(code)) return "Mandarin";
        if ("id".equals(code)) return "Indonesia";
        Locale l = Locale.forLanguageTag(code);
        String name = l.getDisplayLanguage(Locale.forLanguageTag("id"));
        if (name == null || name.isEmpty()) return code;
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    static String scriptFor(String lang) {
        if ("ja".equals(lang)) return SCRIPT_JAPANESE;
        if ("ko".equals(lang)) return SCRIPT_KOREAN;
        if ("zh".equals(lang)) return SCRIPT_CHINESE;
        return SCRIPT_LATIN;
    }

    static List<String> sourceOptions() {
        List<String> out = new ArrayList<>();
        out.add(AUTO);
        out.addAll(Arrays.asList(COMMON_SOURCE));
        out.addAll(rest(COMMON_SOURCE, true));
        return out;
    }

    static List<String> targetOptions() {
        List<String> out = new ArrayList<>(Arrays.asList(COMMON_TARGET));
        out.addAll(rest(COMMON_TARGET, false));
        return out;
    }

    private static List<String> rest(String[] exclude, boolean readableOnly) {
        Set<String> ex = new HashSet<>(Arrays.asList(exclude));
        List<String> all = new ArrayList<>();
        for (String c : TranslateLanguage.getAllLanguages()) {
            if (ex.contains(c)) continue;
            if (readableOnly && UNREADABLE.contains(c)) continue;
            all.add(c);
        }
        Collections.sort(all, (a, b) -> displayName(a).compareTo(displayName(b)));
        return all;
    }

    // ------------------------------------------------------------ jenis huruf

    static boolean isHangul(int cp) {
        return (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x3130 && cp <= 0x318F);
    }

    static boolean isKana(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0x31F0 && cp <= 0x31FF) || (cp >= 0xFF66 && cp <= 0xFF9D);
    }

    static boolean isHan(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FFFF);
    }

    /** Huruf/tanda baca lebar khas Jepang/Mandarin (tidak memakai spasi antarkata). */
    static boolean isCjkWide(int cp) {
        return isKana(cp) || isHan(cp) || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFF65);
    }

    /** [hangul, kana, han, hurufLain] */
    static int[] count(String s) {
        int[] c = new int[4];
        if (s == null) return c;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isHangul(cp)) c[0]++;
            else if (isKana(cp)) c[1]++;
            else if (isHan(cp)) c[2]++;
            else if (Character.isLetter(cp)) c[3]++;
            i += Character.charCount(cp);
        }
        return c;
    }

    static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }
}
