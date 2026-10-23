package id.terjemahlayar;

import android.graphics.Bitmap;
import android.graphics.Rect;

/**
 * Menghapus teks asli dari gambar layar dengan mengisi area teks memakai warna latar di sekelilingnya
 * (warna polos, atau gradasi halus bila latarnya bergradasi), lalu menebak warna huruf aslinya.
 * Hasilnya ditempel tepat di atas teks asli sehingga terjemahan terlihat menyatu dengan layar.
 */
final class Eraser {
    private Eraser() {}

    static int dist(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }

    /** Median per kanal warna. */
    static int median(int[] px, int n) {
        int[] hr = new int[256], hg = new int[256], hb = new int[256];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            hr[(c >> 16) & 0xFF]++;
            hg[(c >> 8) & 0xFF]++;
            hb[c & 0xFF]++;
        }
        return 0xFF000000 | (med(hr, n) << 16) | (med(hg, n) << 8) | med(hb, n);
    }

    private static int med(int[] h, int n) {
        int half = n / 2, acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += h[i];
            if (acc > half) return i;
        }
        return 255;
    }

    /** Warna median di tepi persegi (di luar area teks). */
    static int ringMedian(Bitmap src, Rect r) {
        int[] ring = readRing(src, r);
        return median(ring, ring.length);
    }

    private static int[] readRing(Bitmap src, Rect r) {
        int w = r.width(), h = r.height();
        int[] ring = new int[2 * w + 2 * h];
        int W = src.getWidth(), H = src.getHeight();
        int ty = Math.max(0, r.top - 1), by = Math.min(H - 1, r.bottom);
        int lx = Math.max(0, r.left - 1), rx = Math.min(W - 1, r.right);
        src.getPixels(ring, 0, w, r.left, ty, w, 1);
        src.getPixels(ring, w, w, r.left, by, w, 1);
        src.getPixels(ring, 2 * w, 1, lx, r.top, 1, h);
        src.getPixels(ring, 2 * w + h, 1, rx, r.top, 1, h);
        return ring;
    }

    static float luminance(int c) {
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        r = r <= 0.03928f ? r / 12.92f : (float) Math.pow((r + 0.055f) / 1.055f, 2.4f);
        g = g <= 0.03928f ? g / 12.92f : (float) Math.pow((g + 0.055f) / 1.055f, 2.4f);
        b = b <= 0.03928f ? b / 12.92f : (float) Math.pow((b + 0.055f) / 1.055f, 2.4f);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    static float contrast(int a, int b) {
        float la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05f) / (Math.min(la, lb) + 0.05f);
    }

    /** Hitung tambalan penghapus + warna huruf untuk satu kelompok teks. */
    static void erase(Bitmap src, LayoutAnalyzer.Group g) {
        if (g.erased) return;
        g.erased = true;
        g.patches.clear();
        long fr = 0, fgG = 0, fb = 0, fn = 0;
        int bestArea = -1;
        int W = src.getWidth(), H = src.getHeight();

        java.util.List<Rect> rects = new java.util.ArrayList<>();
        java.util.List<Boolean> isText = new java.util.ArrayList<>();
        for (LayoutAnalyzer.Line l : g.lines) { rects.add(l.r); isText.add(true); }
        for (Rect r : g.extraErase) { rects.add(r); isText.add(false); }

        for (int k = 0; k < rects.size(); k++) {
            Rect inner = new Rect(rects.get(k));
            float thick = Math.min(inner.width(), inner.height());
            int pad = Math.round(0.15f * thick) + 2;
            Rect r = new Rect(inner.left - pad, inner.top - pad, inner.right + pad, inner.bottom + pad);
            if (!r.intersect(0, 0, W, H) || r.width() < 2 || r.height() < 2) continue;
            if (!inner.intersect(0, 0, W, H)) continue;

            int w = r.width(), h = r.height();
            int[] ring = readRing(src, r);
            int bg = median(ring, ring.length);
            int near = 0;
            for (int c : ring) if (dist(c, bg) <= 24) near++;
            // Buang coretan huruf yang menyentuh tepi, tanpa meratakan gradasi.
            cleanEdge(ring, 0, w);
            cleanEdge(ring, w, w);
            cleanEdge(ring, 2 * w, h);
            cleanEdge(ring, 2 * w + h, h);
            int[] out = new int[w * h];
            if (near >= 0.8f * ring.length) {
                java.util.Arrays.fill(out, bg);
            } else {
                coons(ring, w, h, out);
            }
            g.patches.add(new LayoutAnalyzer.Patch(r, Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)));

            if (w * h > bestArea) {
                bestArea = w * h;
                g.bg = bg;
            }
            if (!isText.get(k)) continue;
            // Warna huruf: rata-rata piksel yang jelas berbeda dari latar.
            int iw = inner.width(), ih = inner.height();
            int[] px = new int[iw * ih];
            src.getPixels(px, 0, iw, inner.left, inner.top, iw, ih);
            for (int y = 0; y < ih; y += 2) {
                for (int x = 0; x < iw; x += 2) {
                    int c = px[y * iw + x];
                    if (dist(c, bg) > 80) {
                        fr += (c >> 16) & 0xFF;
                        fgG += (c >> 8) & 0xFF;
                        fb += c & 0xFF;
                        fn++;
                    }
                }
            }
        }
        if (fn >= 12) {
            g.fg = 0xFF000000 | ((int) (fr / fn) << 16) | ((int) (fgG / fn) << 8) | (int) (fb / fn);
        } else {
            g.fg = luminance(g.bg) > 0.4f ? 0xFF000000 : 0xFFFFFFFF;
        }
        if (contrast(g.fg, g.bg) < 2.5f) {
            g.fg = luminance(g.bg) > 0.4f ? 0xFF111111 : 0xFFFFFFFF;
        }
    }

    /** Ganti piksel tepi yang jauh berbeda dari median lokal di sekitarnya. */
    private static void cleanEdge(int[] a, int off, int len) {
        if (len < 3) return;
        int k = Math.max(3, len / 12);
        int[] copy = java.util.Arrays.copyOfRange(a, off, off + len);
        int[] r = new int[2 * k + 1], g = new int[2 * k + 1], b = new int[2 * k + 1];
        for (int i = 0; i < len; i++) {
            int n = 0;
            for (int j = Math.max(0, i - k); j <= Math.min(len - 1, i + k); j++) {
                int c = copy[j];
                r[n] = (c >> 16) & 0xFF;
                g[n] = (c >> 8) & 0xFF;
                b[n] = c & 0xFF;
                n++;
            }
            java.util.Arrays.sort(r, 0, n);
            java.util.Arrays.sort(g, 0, n);
            java.util.Arrays.sort(b, 0, n);
            int m = 0xFF000000 | (r[n / 2] << 16) | (g[n / 2] << 8) | b[n / 2];
            if (dist(copy[i], m) > 40) a[off + i] = m;
        }
    }

    /** Isi area dengan interpolasi dari keempat tepinya (Coons patch) — rapi untuk latar bergradasi. */
    private static void coons(int[] ring, int w, int h, int[] out) {
        int T = 0, B = w, L = 2 * w, R = 2 * w + h;
        int p00 = ring[T], p10 = ring[T + w - 1], p01 = ring[B], p11 = ring[B + w - 1];
        for (int y = 0; y < h; y++) {
            float v = h > 1 ? y / (float) (h - 1) : 0f;
            int lc = ring[L + y], rc = ring[R + y];
            for (int x = 0; x < w; x++) {
                float u = w > 1 ? x / (float) (w - 1) : 0f;
                int tc = ring[T + x], bc = ring[B + x];
                int c = 0xFF000000;
                for (int sh = 16; sh >= 0; sh -= 8) {
                    float val = (1 - v) * ((tc >> sh) & 0xFF) + v * ((bc >> sh) & 0xFF)
                            + (1 - u) * ((lc >> sh) & 0xFF) + u * ((rc >> sh) & 0xFF)
                            - ((1 - u) * (1 - v) * ((p00 >> sh) & 0xFF) + u * (1 - v) * ((p10 >> sh) & 0xFF)
                            + (1 - u) * v * ((p01 >> sh) & 0xFF) + u * v * ((p11 >> sh) & 0xFF));
                    int iv = Math.max(0, Math.min(255, Math.round(val)));
                    c |= iv << sh;
                }
                out[y * w + x] = c;
            }
        }
    }
}
