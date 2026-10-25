package id.terjemahlayar;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Menyusun baris hasil OCR menjadi kelompok kalimat/paragraf:
 * - baris yang menyambung ke bawah (paragraf, gelembung komik rata tengah) digabung jadi satu kalimat,
 * - kolom vertikal (manga) dibaca atas→bawah, kolom kanan→kiri,
 * - furigana (kana kecil di samping kanji) ditandai agar ikut dihapus tetapi tidak diterjemahkan.
 */
final class LayoutAnalyzer {

    static final class Line {
        final Rect r;
        final String text;
        final int block;
        boolean vertical;
        final int cps;
        final boolean cjk;

        Line(Rect r, String text, int block, boolean vertical) {
            this.r = r;
            this.text = text;
            this.block = block;
            this.cps = text.codePointCount(0, text.length());
            int[] c = Langs.count(text);
            this.cjk = (c[1] + c[2]) > 0 && (c[1] + c[2]) >= c[3];
            this.vertical = vertical;
        }

        float thick() { return vertical ? r.width() : r.height(); }
    }

    static final class Patch {
        final Rect r;
        final Bitmap bmp;

        Patch(Rect r, Bitmap bmp) {
            this.r = r;
            this.bmp = bmp;
        }
    }

    static final class Group {
        final List<Line> lines = new ArrayList<>();
        /** Area tambahan yang ikut dihapus tetapi tidak dibaca (mis. furigana pada kolom manga). */
        final List<Rect> extraErase = new ArrayList<>();
        final Rect bounds = new Rect();
        boolean vertical;
        boolean centered;
        boolean ruby;
        String text = "";
        int hangul, kana, han, latin;
        float thickness;

        // diisi saat penerjemahan
        String lang;
        String translated;

        // diisi saat menghapus teks asli
        final List<Patch> patches = new ArrayList<>();
        boolean erased;
        /** Area kosong di sekitar teks (mis. bagian dalam gelembung) tempat terjemahan boleh ditulis. */
        Rect free;
        int fg = 0xFF000000;
        int bg = 0xFFFFFFFF;

        void finish() {
            bounds.setEmpty();
            for (Line l : lines) bounds.union(l.r);
            int[] c = Langs.count(text);
            hangul = c[0];
            kana = c[1];
            han = c[2];
            latin = c[3];
            List<Float> t = new ArrayList<>();
            for (Line l : lines) t.add(l.thick());
            Collections.sort(t);
            thickness = t.isEmpty() ? 0 : t.get(t.size() / 2);
        }

        boolean isCjk() { return kana + han > 0; }
    }

    private LayoutAnalyzer() {}

    static List<Group> analyze(Text text, String script) {
        List<Line> lines = new ArrayList<>();
        int bi = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line tl : block.getLines()) {
                String t = tl.getText() == null ? "" : tl.getText().trim();
                Rect r = tl.getBoundingBox();
                if (t.isEmpty() || r == null || r.width() <= 0 || r.height() <= 0) continue;
                Line l = new Line(new Rect(r), t, bi, false);
                l.vertical = l.cjk && l.cps >= 2 && r.height() > 1.4f * r.width();
                lines.add(l);
            }
            bi++;
        }

        boolean cjkScript = Langs.SCRIPT_JAPANESE.equals(script) || Langs.SCRIPT_CHINESE.equals(script);
        if (cjkScript) {
            int v = 0, h = 0;
            for (Line l : lines) {
                if (!l.cjk) continue;
                if (l.vertical) v++;
                else if (l.cps >= 3) h++;
            }
            if (v > 0 && v >= h) {
                // Halaman bertulisan vertikal: potongan 1–2 huruf dianggap bagian dari kolom.
                for (Line l : lines) {
                    if (l.cjk && !l.vertical && (l.cps == 1 || l.r.height() >= 0.75f * l.r.width())) {
                        l.vertical = true;
                    }
                }
            }
        }

        // Union-find: gabungkan baris yang saling menyambung.
        int n = lines.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (joinable(lines.get(i), lines.get(j))) union(parent, i, j);
            }
        }
        List<List<Line>> sets = new ArrayList<>();
        int[] index = new int[n];
        java.util.Arrays.fill(index, -1);
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            if (index[root] < 0) {
                index[root] = sets.size();
                sets.add(new ArrayList<>());
            }
            sets.get(index[root]).add(lines.get(i));
        }

        List<Group> groups = new ArrayList<>();
        for (List<Line> set : sets) {
            Group g = new Group();
            g.vertical = set.get(0).vertical;
            if (g.vertical) orderVertical(set, g);
            else orderHorizontal(set, g);
            g.finish();
            if (!Langs.hasLetter(g.text)) continue;
            groups.add(g);
        }

        markRuby(groups);
        return groups;
    }

    // ------------------------------------------------------------ aturan penggabungan

    private static boolean joinable(Line a, Line b) {
        if (a.vertical != b.vertical) return false;
        float ta = a.thick(), tb = b.thick();
        if (Math.max(ta, tb) > 1.5f * Math.min(ta, tb)) return false;
        float t = (ta + tb) / 2f;
        boolean sameBlock = a.block == b.block;

        if (!a.vertical) {
            Line up = a.r.top <= b.r.top ? a : b;
            Line lo = up == a ? b : a;
            float vgap = lo.r.top - up.r.bottom;
            int xov = Math.min(a.r.right, b.r.right) - Math.max(a.r.left, b.r.left);
            int minW = Math.min(a.r.width(), b.r.width());
            int maxW = Math.max(a.r.width(), b.r.width());
            int yov = Math.min(a.r.bottom, b.r.bottom) - Math.max(a.r.top, b.r.top);
            boolean sameRow = yov >= 0.6f * Math.min(a.r.height(), b.r.height());

            if (!sameRow && vgap >= -0.5f * t && vgap <= 0.9f * t && xov >= 0.25f * minW) {
                if (sameBlock) return true;
                // Gelembung komik / teks rata tengah: pusat baris sejajar.
                float dc = Math.abs(a.r.centerX() - b.r.centerX());
                float dl = Math.abs(a.r.left - b.r.left);
                return dc <= 0.12f * maxW && dl > dc;
            }
            if (sameRow && sameBlock) {
                Line le = a.r.left <= b.r.left ? a : b;
                Line ri = le == a ? b : a;
                float hgap = ri.r.left - le.r.right;
                return hgap >= -t && hgap <= 1.2f * t;
            }
            return false;
        } else {
            // Kolom bersebelahan
            int yov = Math.min(a.r.bottom, b.r.bottom) - Math.max(a.r.top, b.r.top);
            int minH = Math.min(a.r.height(), b.r.height());
            Line le = a.r.left <= b.r.left ? a : b;
            Line ri = le == a ? b : a;
            float hgap = ri.r.left - le.r.right;
            int xov = Math.min(a.r.right, b.r.right) - Math.max(a.r.left, b.r.left);
            boolean sameColumn = xov >= 0.5f * Math.min(a.r.width(), b.r.width());
            if (!sameColumn && yov >= 0.2f * minH && hgap >= -0.4f * t && hgap <= 0.9f * t) return true;
            // Lanjutan di kolom yang sama
            if (sameColumn) {
                Line up = a.r.top <= b.r.top ? a : b;
                Line lo = up == a ? b : a;
                float vgap = lo.r.top - up.r.bottom;
                return vgap >= -0.5f * t && vgap <= 1.0f * t;
            }
            return false;
        }
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[rb] = ra;
    }

    // ------------------------------------------------------------ urutan baca

    private static void orderHorizontal(List<Line> set, Group g) {
        List<Line> sorted = new ArrayList<>(set);
        Collections.sort(sorted, (x, y) -> Integer.compare(x.r.top, y.r.top));
        List<List<Line>> rows = new ArrayList<>();
        for (Line l : sorted) {
            List<Line> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null) {
                Line ref = last.get(0);
                int yov = Math.min(ref.r.bottom, l.r.bottom) - Math.max(ref.r.top, l.r.top);
                if (yov >= 0.5f * Math.min(ref.r.height(), l.r.height())) {
                    last.add(l);
                    continue;
                }
            }
            List<Line> row = new ArrayList<>();
            row.add(l);
            rows.add(row);
        }
        StringBuilder sb = new StringBuilder();
        float leftMin = Float.MAX_VALUE, leftMax = -Float.MAX_VALUE, cMin = Float.MAX_VALUE, cMax = -Float.MAX_VALUE;
        for (List<Line> row : rows) {
            Collections.sort(row, (x, y) -> Integer.compare(x.r.left, y.r.left));
            Rect rr = new Rect();
            for (Line l : row) {
                appendJoined(sb, l.text);
                g.lines.add(l);
                rr.union(l.r);
            }
            leftMin = Math.min(leftMin, rr.left);
            leftMax = Math.max(leftMax, rr.left);
            cMin = Math.min(cMin, rr.exactCenterX());
            cMax = Math.max(cMax, rr.exactCenterX());
        }
        g.text = sb.toString();
        if (rows.size() >= 2) {
            g.centered = (cMax - cMin) < 0.7f * (leftMax - leftMin);
        } else {
            int[] c = Langs.count(g.text);
            g.centered = (c[0] + c[1] + c[2]) > c[3]; // satu baris CJK/Korea: umumnya gelembung komik
        }
    }

    private static void orderVertical(List<Line> set, Group g) {
        List<Line> sorted = new ArrayList<>(set);
        Collections.sort(sorted, (x, y) -> Float.compare(y.r.exactCenterX(), x.r.exactCenterX()));
        List<List<Line>> cols = new ArrayList<>();
        List<Rect> colRects = new ArrayList<>();
        for (Line l : sorted) {
            int found = -1;
            for (int i = 0; i < cols.size(); i++) {
                Rect cr = colRects.get(i);
                int xov = Math.min(cr.right, l.r.right) - Math.max(cr.left, l.r.left);
                if (xov >= 0.5f * Math.min(cr.width(), l.r.width())) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                cols.add(new ArrayList<>());
                colRects.add(new Rect(l.r));
                found = cols.size() - 1;
            } else {
                colRects.get(found).union(l.r);
            }
            cols.get(found).add(l);
        }
        // kanan → kiri
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) order.add(i);
        Collections.sort(order, (x, y) -> Float.compare(colRects.get(y).exactCenterX(), colRects.get(x).exactCenterX()));
        StringBuilder sb = new StringBuilder();
        for (int ci : order) {
            List<Line> col = cols.get(ci);
            Collections.sort(col, (x, y) -> Integer.compare(x.r.top, y.r.top));
            for (Line l : col) {
                appendJoined(sb, l.text);
                g.lines.add(l);
            }
        }
        g.text = sb.toString();
        g.centered = true;
    }

    /** Sambungkan potongan teks: tanpa spasi untuk Jepang/Mandarin, gabungkan kata berpotong tanda hubung. */
    static void appendJoined(StringBuilder sb, String next) {
        if (next.isEmpty()) return;
        if (sb.length() == 0) {
            sb.append(next);
            return;
        }
        int a = sb.codePointBefore(sb.length());
        int b = next.codePointAt(0);
        if (Langs.isCjkWide(a) || Langs.isCjkWide(b)) {
            sb.append(next);
        } else if (a == '-' && sb.length() >= 2 && Character.isLetter(sb.charAt(sb.length() - 2))
                && Character.isLowerCase(b)) {
            sb.setLength(sb.length() - 1);
            sb.append(next);
        } else {
            sb.append(' ').append(next);
        }
    }

    /** Furigana: kelompok kecil berisi kana saja yang menempel pada teks yang jauh lebih besar. */
    private static void markRuby(List<Group> groups) {
        for (Group g : groups) {
            if (!(g.kana > 0 && g.han == 0 && g.hangul == 0 && g.latin == 0)) continue;
            if (g.text.codePointCount(0, g.text.length()) > 12) continue;
            for (Group h : groups) {
                if (h == g || h.thickness < 1.6f * g.thickness) continue;
                if (distance(g.bounds, h.bounds) <= 0.8f * h.thickness) {
                    g.ruby = true;
                    break;
                }
            }
        }
    }

    static float distance(Rect a, Rect b) {
        int dx = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        int dy = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
        return Math.max(dx, dy);
    }
}
