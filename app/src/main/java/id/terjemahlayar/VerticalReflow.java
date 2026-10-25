package id.terjemahlayar;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Teks vertikal (manga Jepang/Mandarin): OCR bawaan sering salah membaca kolom vertikal.
 * Kelas ini memotong tiap kolom menjadi huruf-huruf, lalu menyusunnya ulang menjadi baris horizontal
 * (satu kolom = satu baris, urutan kanan → kiri) pada gambar baru yang bersih, kemudian gambar itu dibaca ulang.
 */
final class VerticalReflow {

    static final class Job {
        final List<LayoutAnalyzer.Group> source;
        final List<Rect> columns = new ArrayList<>(); // koordinat layar, urut kanan → kiri
        final List<Rect> ruby = new ArrayList<>();
        Bitmap tiled;
        int margin, rowH;
        float s;

        Job(List<LayoutAnalyzer.Group> source) { this.source = source; }

        /** Ubah hasil OCR gambar susunan-ulang menjadi teks berurutan. */
        String parse(Text result) {
            List<Object[]> parts = new ArrayList<>();
            for (Text.TextBlock b : result.getTextBlocks()) {
                for (Text.Line l : b.getLines()) {
                    Rect r = l.getBoundingBox();
                    if (r == null || l.getText() == null) continue;
                    int k = Math.max(0, Math.min(columns.size() - 1, (r.centerY() - margin) / rowH));
                    parts.add(new Object[]{k, r.left, l.getText()});
                }
            }
            Collections.sort(parts, (a, b) -> {
                int c = Integer.compare((Integer) a[0], (Integer) b[0]);
                return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
            });
            StringBuilder sb = new StringBuilder();
            for (Object[] p : parts) LayoutAnalyzer.appendJoined(sb, ((String) p[2]).replaceAll("\\s+", ""));
            return fixLongVowel(sb.toString());
        }
    }

    private VerticalReflow() {}

    /** Tanda panjang "ー" pada teks vertikal berbentuk garis tegak; setelah disusun ulang sering terbaca "|" atau "l". */
    private static String fixLongVowel(String s) {
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if ("|｜丨lI1!！".indexOf(c) < 0) continue;
            boolean prevKana = i > 0 && Langs.isKana(sb.charAt(i - 1));
            boolean nextKana = i + 1 < sb.length() && Langs.isKana(sb.charAt(i + 1));
            if (prevKana || (nextKana && i > 0 && Langs.isCjkWide(sb.charAt(i - 1)))) sb.setCharAt(i, 'ー');
        }
        return sb.toString();
    }

    /**
     * Periksa apakah area berisi teks vertikal; jika ya siapkan gambar susunan-ulang.
     * Mengembalikan null jika teksnya horizontal atau tidak bisa diproses.
     */
    static Job prepare(Bitmap src, List<LayoutAnalyzer.Group> cluster, Rect bounds, float sGuess, boolean forceVertical) {
        return build(src, cluster, bounds, sGuess, forceVertical, true);
    }

    private static Job build(Bitmap src, List<LayoutAnalyzer.Group> cluster, Rect bounds, float sGuess,
                             boolean forceVertical, boolean allowGrow) {
        int pad = Math.round(0.4f * sGuess) + 2;
        Rect region = new Rect(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad);
        if (!region.intersect(0, 0, src.getWidth(), src.getHeight())) return null;
        int W = region.width(), H = region.height();
        if (W < 8 || H < 8 || (long) W * H > 3_000_000L) return null;

        int[] px = new int[W * H];
        src.getPixels(px, 0, W, region.left, region.top, W, H);
        int bg = Eraser.ringMedian(src, region);
        // Tentukan warna tinta: gelap di atas terang (umumnya) atau terang di atas gelap.
        // Teks hitam berpinggiran putih di atas latar abu-abu hanya diambil bagian gelapnya.
        int bgGray = gray(bg);
        int darkN = 0, lightN = 0;
        for (int c : px) {
            int d = gray(c) - bgGray;
            if (d < -60) darkN++;
            else if (d > 60) lightN++;
        }
        boolean darkInk = !(bgGray < 70 || (darkN < 0.01f * px.length && lightN > darkN));
        boolean[] ink = new boolean[W * H];
        for (int i = 0; i < px.length; i++) ink[i] = isInk(px[i], bgGray, darkInk);
        removeBorderComponents(ink, W, H);
        removeSpecks(ink, W, H, Math.max(2, Math.round(0.12f * sGuess)));

        int[] colInk = new int[W], rowInk = new int[H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (ink[y * W + x]) {
                    colInk[x]++;
                    rowInk[y]++;
                }
            }
        }
        int merge = Math.max(1, Math.round(0.06f * sGuess));
        List<int[]> xRuns = runs(colInk, merge);
        List<int[]> yRuns = runs(rowInk, merge);
        if (xRuns.isEmpty() || yRuns.isEmpty()) return null;

        // Ukuran huruf perkiraan dari lebar kolom tinta
        List<Integer> widths = new ArrayList<>();
        for (int[] r : xRuns) widths.add(r[1] - r[0]);
        Collections.sort(widths);
        float s = Math.max(6, widths.get((int) (0.75f * (widths.size() - 1))));

        if (!forceVertical) {
            float aspectCols = medianAspect(ink, W, H, xRuns, true);
            float aspectRows = medianAspect(ink, W, H, yRuns, false);
            int gapX = medianGap(xRuns), gapY = medianGap(yRuns);
            boolean vertical;
            if (gapX > 1.3f * gapY && aspectCols >= 1.5f) vertical = true;
            else if (gapY > 1.3f * gapX && aspectRows >= 1.5f) vertical = false;
            else vertical = aspectCols > aspectRows;
            if (!vertical) return null;
        }

        // Kolom: gabungkan potongan hanya selama lebarnya masih ±1 huruf, supaya furigana yang menempel terpisah.
        List<int[]> colRuns = new ArrayList<>();
        for (int[] r : runs(colInk, 0)) {
            int[] last = colRuns.isEmpty() ? null : colRuns.get(colRuns.size() - 1);
            if (last != null && r[0] - last[1] <= 0.4f * sGuess && r[1] - last[0] <= 1.15f * sGuess) last[1] = r[1];
            else colRuns.add(new int[]{r[0], r[1]});
        }
        // Kolom yang saling menempel (tanpa celah) dibelah sesuai lebar satu huruf.
        colRuns = splitByPitch(colRuns, colInk, sGuess);
        List<Integer> cw0 = new ArrayList<>();
        for (int[] r : colRuns) if (r[1] - r[0] >= 0.5f * sGuess) cw0.add(r[1] - r[0]);
        if (!cw0.isEmpty()) {
            Collections.sort(cw0);
            s = Math.max(6, cw0.get(cw0.size() / 2));
        }

        Job job = new Job(cluster);
        List<int[]> mainCols = new ArrayList<>();
        for (int[] r : colRuns) {
            int w = r[1] - r[0];
            if (w >= 0.6f * s) mainCols.add(r);
            else if (w >= 0.2f * s) {
                int[] ext = extent(ink, W, r[0], r[1], 0, H, true);
                if (ext != null) job.ruby.add(new Rect(region.left + r[0], region.top + ext[0], region.left + r[1], region.top + ext[1]));
            }
        }
        if (mainCols.isEmpty()) return null;
        List<Integer> mw = new ArrayList<>();
        for (int[] r : mainCols) mw.add(r[1] - r[0]);
        Collections.sort(mw);
        s = Math.max(6, mw.get(mw.size() / 2));
        int maxColW = mw.get(mw.size() - 1);
        job.s = s;

        // Potong tiap kolom menjadi huruf (kanan → kiri)
        Collections.reverse(mainCols);
        List<List<int[]>> chars = new ArrayList<>();
        int maxChars = 0;
        for (int[] c : mainCols) {
            int[] prof = new int[H];
            for (int y = 0; y < H; y++) {
                int cnt = 0;
                for (int x = c[0]; x < c[1]; x++) if (ink[y * W + x]) cnt++;
                prof[y] = cnt;
            }
            List<int[]> segs = runs(prof, Math.max(1, Math.round(0.08f * s)));
            List<int[]> cells = new ArrayList<>();
            int[] cur = null;
            for (int[] seg : segs) {
                if (cur == null) cur = new int[]{seg[0], seg[1]};
                else if (seg[1] - cur[0] <= 1.25f * s) cur[1] = seg[1];
                else {
                    cells.add(cur);
                    cur = new int[]{seg[0], seg[1]};
                }
            }
            if (cur != null) cells.add(cur);
            cells = splitByPitch(cells, prof, s);
            if (cells.isEmpty()) continue;
            chars.add(cells);
            job.columns.add(new Rect(region.left + c[0], region.top + cells.get(0)[0],
                    region.left + c[1], region.top + cells.get(cells.size() - 1)[1]));
            maxChars = Math.max(maxChars, cells.size());
        }
        if (chars.isEmpty()) return null;

        if (allowGrow) {
            // Kotak OCR sering tidak menutupi kolom sampai ujung: telusuri kolom ke atas/bawah di gambar asli.
            Rect grown = new Rect();
            for (Rect c : job.columns) grown.union(extendColumn(src, bgGray, darkInk, c, s));
            grown.left -= Math.round(1.0f * s);   // cari juga kolom yang terlewat di kiri/kanan
            grown.right += Math.round(1.0f * s);
            Rect cur = new Rect(region);
            if (grown.top < cur.top + pad / 2 || grown.bottom > cur.bottom - pad / 2) {
                Job again = build(src, cluster, grown, s, true, false);
                if (again != null) return again;
            }
        }

        // Susun ulang menjadi baris horizontal di gambar bersih (hitam di atas putih)
        int scale = s < 28 ? 2 : 1;
        int cell = Math.round(Math.max(1.2f * s, 1.1f * maxColW) * scale);
        int rowH = Math.round(1.7f * s * scale);
        int margin = Math.round(0.6f * s * scale);
        int outW = 2 * margin + maxChars * cell;
        int outH = 2 * margin + chars.size() * rowH;
        if (outW > 8000 || (long) outW * outH > 12_000_000L) return null;
        int[] out = new int[outW * outH];
        java.util.Arrays.fill(out, 0xFFFFFFFF);
        for (int k = 0; k < chars.size(); k++) {
            int[] col = mainCols.get(k);
            List<int[]> cells = chars.get(k);
            for (int i = 0; i < cells.size(); i++) {
                int[] ch = cells.get(i);
                int cw = col[1] - col[0], chh = ch[1] - ch[0];
                int dx0 = margin + i * cell + (cell - cw * scale) / 2;
                int dy0 = margin + k * rowH + (rowH - chh * scale) / 2;
                for (int yy = 0; yy < chh * scale; yy++) {
                    int dy = dy0 + yy;
                    if (dy < 0 || dy >= outH) continue;
                    int sy = ch[0] + yy / scale;
                    for (int xx = 0; xx < cw * scale; xx++) {
                        int dx = dx0 + xx;
                        if (dx < 0 || dx >= outW) continue;
                        int sx = col[0] + xx / scale;
                        if (ink[sy * W + sx]) out[dy * outW + dx] = 0xFF000000;
                    }
                }
            }
        }
        job.tiled = Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888);
        job.margin = margin;
        job.rowH = rowH;
        return job;
    }

    /** Perpanjang kolom ke atas & bawah selama masih ada huruf (berhenti di celah besar atau garis gelembung). */
    private static int gray(int c) {
        return (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
    }

    private static boolean isInk(int c, int bgGray, boolean darkInk) {
        int d = gray(c) - bgGray;
        return darkInk ? d < -60 : d > 60;
    }

    /**
     * Huruf manga sering sangat rapat sehingga beberapa huruf menyatu jadi satu potongan.
     * Potongan yang jauh lebih tinggi dari satu huruf dibelah sesuai ukuran huruf, di baris yang paling sedikit tintanya.
     */
    private static List<int[]> splitByPitch(List<int[]> cells, int[] prof, float s) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : cells) {
            int h = c[1] - c[0];
            int n = Math.round(h / (1.08f * s));
            if (n <= 1) {
                out.add(c);
                continue;
            }
            int start = c[0];
            int win = Math.max(1, Math.round(0.22f * s));
            for (int k = 1; k < n; k++) {
                int exp = c[0] + Math.round(k * h / (float) n);
                int best = exp, bestV = Integer.MAX_VALUE;
                for (int y = Math.max(start + 1, exp - win); y <= Math.min(c[1] - 1, exp + win); y++) {
                    int v = prof[y] * 4 + Math.abs(y - exp);
                    if (v < bestV) {
                        bestV = v;
                        best = y;
                    }
                }
                out.add(new int[]{start, best});
                start = best;
            }
            out.add(new int[]{start, c[1]});
        }
        return out;
    }

    private static Rect extendColumn(Bitmap src, int bgGray, boolean darkInk, Rect col, float s) {
        int W = src.getWidth(), H = src.getHeight();
        int m = Math.max(2, Math.round(0.15f * s));
        int x0 = Math.max(0, col.left - m), x1 = Math.min(W, col.right + m);
        int w = x1 - x0;
        int[] row = new int[w];
        int maxGap = Math.round(0.9f * s);
        int limit = Math.round(Math.max(3 * s, 0.6f * col.height()));
        int bottom = col.bottom, top = col.top;
        for (int dir = 1; dir >= -1; dir -= 2) {
            int lastInk = dir > 0 ? col.bottom - 1 : col.top;
            for (int i = 0; i < limit; i++) {
                int y = dir > 0 ? col.bottom + i : col.top - 1 - i;
                if (y < 0 || y >= H) break;
                if (Math.abs(y - lastInk) > maxGap) break;
                src.getPixels(row, 0, w, x0, y, w, 1);
                boolean inside = false, outsideL = false, outsideR = false;
                for (int x = 0; x < w; x++) {
                    if (!isInk(row[x], bgGray, darkInk)) continue;
                    int sx = x0 + x;
                    if (sx < col.left) outsideL = true;
                    else if (sx >= col.right) outsideR = true;
                    else inside = true;
                }
                if (inside && (outsideL || outsideR)) break; // garis yang keluar dari kolom (tepi gelembung, gambar)
                if (inside) lastInk = y;
            }
            if (dir > 0) bottom = lastInk + 1;
            else top = lastInk;
        }
        return new Rect(col.left, top, col.right, bottom);
    }

    /** Buang bintik kecil (screentone, noise) yang lebarnya dan tingginya di bawah maxDim piksel. */
    private static void removeSpecks(boolean[] ink, int W, int H, int maxDim) {
        boolean[] seen = new boolean[W * H];
        int[] queue = new int[W * H];
        for (int start = 0; start < ink.length; start++) {
            if (!ink[start] || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int minX = W, maxX = -1, minY = H, maxY = -1;
            boolean big = false;
            while (head < tail) {
                int p = queue[head++];
                int x = p % W, y = p / W;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (maxX - minX >= maxDim || maxY - minY >= maxDim) big = true;
                if (x > 0 && ink[p - 1] && !seen[p - 1]) { seen[p - 1] = true; queue[tail++] = p - 1; }
                if (x < W - 1 && ink[p + 1] && !seen[p + 1]) { seen[p + 1] = true; queue[tail++] = p + 1; }
                if (y > 0 && ink[p - W] && !seen[p - W]) { seen[p - W] = true; queue[tail++] = p - W; }
                if (y < H - 1 && ink[p + W] && !seen[p + W]) { seen[p + W] = true; queue[tail++] = p + W; }
            }
            if (!big) for (int i = 0; i < tail; i++) ink[queue[i]] = false;
        }
    }

    /** Buang tinta yang menyentuh tepi area (garis gelembung, gambar di sekitar teks). */
    private static void removeBorderComponents(boolean[] ink, int W, int H) {
        int[] queue = new int[W * H];
        int head = 0, tail = 0;
        for (int x = 0; x < W; x++) {
            if (ink[x]) { ink[x] = false; queue[tail++] = x; }
            int b = (H - 1) * W + x;
            if (ink[b]) { ink[b] = false; queue[tail++] = b; }
        }
        for (int y = 0; y < H; y++) {
            int l = y * W, r = y * W + W - 1;
            if (ink[l]) { ink[l] = false; queue[tail++] = l; }
            if (ink[r]) { ink[r] = false; queue[tail++] = r; }
        }
        while (head < tail) {
            int p = queue[head++];
            int x = p % W, y = p / W;
            if (x > 0 && ink[p - 1]) { ink[p - 1] = false; queue[tail++] = p - 1; }
            if (x < W - 1 && ink[p + 1]) { ink[p + 1] = false; queue[tail++] = p + 1; }
            if (y > 0 && ink[p - W]) { ink[p - W] = false; queue[tail++] = p - W; }
            if (y < H - 1 && ink[p + W]) { ink[p + W] = false; queue[tail++] = p + W; }
        }
    }

    /** Rentang [awal, akhir) yang berisi tinta; celah kecil (≤ merge) digabung. */
    private static List<int[]> runs(int[] prof, int merge) {
        List<int[]> out = new ArrayList<>();
        int start = -1, lastInk = -1;
        for (int i = 0; i < prof.length; i++) {
            if (prof[i] > 0) {
                if (start < 0) start = i;
                else if (i - lastInk - 1 > merge) {
                    out.add(new int[]{start, lastInk + 1});
                    start = i;
                }
                lastInk = i;
            }
        }
        if (start >= 0) out.add(new int[]{start, lastInk + 1});
        return out;
    }

    /** Median celah antar-potongan (lebih tahan terhadap spasi/tanda baca daripada nilai maksimum). */
    private static int medianGap(List<int[]> runs) {
        if (runs.size() < 2) return 0;
        List<Integer> g = new ArrayList<>();
        for (int i = 1; i < runs.size(); i++) g.add(runs.get(i)[0] - runs.get(i - 1)[1]);
        Collections.sort(g);
        return g.get(g.size() / 2);
    }

    /** Rentang tinta pada arah tegak lurus untuk satu potongan. */
    private static int[] extent(boolean[] ink, int W, int a, int b, int from, int to, boolean columns) {
        int lo = Integer.MAX_VALUE, hi = -1;
        if (columns) {
            for (int y = from; y < to; y++)
                for (int x = a; x < b; x++)
                    if (ink[y * W + x]) { lo = Math.min(lo, y); hi = Math.max(hi, y); }
        } else {
            for (int y = a; y < b; y++)
                for (int x = from; x < to; x++)
                    if (ink[y * W + x]) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
        }
        return hi < 0 ? null : new int[]{lo, hi + 1};
    }

    private static float medianAspect(boolean[] ink, int W, int H, List<int[]> runs, boolean columns) {
        List<Float> asp = new ArrayList<>();
        for (int[] r : runs) {
            int[] e = extent(ink, W, r[0], r[1], 0, columns ? H : W, columns);
            if (e == null) continue;
            asp.add((e[1] - e[0]) / (float) Math.max(1, r[1] - r[0]));
        }
        if (asp.isEmpty()) return 0;
        Collections.sort(asp);
        return asp.get(asp.size() / 2);
    }
}
