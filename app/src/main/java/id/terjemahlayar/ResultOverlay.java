package id.terjemahlayar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import id.terjemahlayar.LayoutAnalyzer.Group;

/**
 * Lapisan layar penuh: teks asli dihapus (diisi warna latar di sekitarnya) lalu terjemahan ditulis
 * di posisi & ukuran yang sama tanpa kotak, sehingga menyatu dengan layar seperti Google Lens.
 * Ketuk = tutup · Tahan = intip teks asli · Atas: pilih bahasa · Bawah: Tutup · Daftar · Salin · Lens.
 */
@SuppressLint("ViewConstructor")
final class ResultOverlay extends FrameLayout {

    interface Actions {
        void onClose();
        void onCopy(String allText);
        void onLens();
        void onLanguagesChanged(String source, String target);
    }

    private final Actions actions;
    private final TranslationLayer layer;
    private final TextView chip;
    private final LinearLayout langPanel;
    private final ScrollView listPanel;
    private final LinearLayout listContent;
    private final LinearLayout bottom;
    private final LangAdapter srcAdapter, tgtAdapter;
    private List<Group> groups = new ArrayList<>();
    private String source, target, detected;
    private boolean busy;

    ResultOverlay(Context ctx, String source, String target, Actions actions) {
        super(ctx);
        this.actions = actions;
        this.source = source;
        this.target = target;

        layer = new TranslationLayer(ctx);
        addView(layer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- tombol bahasa di atas
        chip = new TextView(ctx);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setBackground(rounded(0xE6202124, dp(20)));
        chip.setPadding(dp(16), dp(9), dp(16), dp(9));
        chip.setOnClickListener(v -> toggleLanguages());
        LayoutParams clp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addView(chip, clp);

        // ---- panel pilih bahasa
        langPanel = new LinearLayout(ctx);
        langPanel.setOrientation(LinearLayout.VERTICAL);
        langPanel.setBackground(rounded(0xFFFFFFFF, dp(16)));
        langPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        langPanel.setVisibility(GONE);
        langPanel.setClickable(true);
        TextView title = new TextView(ctx);
        title.setText("Pilih bahasa — terjemahan diperbarui otomatis");
        title.setTextColor(0xFF5F6368);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setPadding(dp(4), 0, 0, dp(8));
        langPanel.addView(title);
        LinearLayout cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        srcAdapter = new LangAdapter(Langs.sourceOptions(), source);
        tgtAdapter = new LangAdapter(Langs.targetOptions(), target);
        cols.addView(langColumn("Dari", srcAdapter, code -> {
            this.source = code;
            srcAdapter.setSelected(code);
            actions.onLanguagesChanged(this.source, this.target);
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View sep = new View(ctx);
        sep.setBackgroundColor(0xFFE0E0E0);
        cols.addView(sep, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        cols.addView(langColumn("Ke", tgtAdapter, code -> {
            this.target = code;
            tgtAdapter.setSelected(code);
            actions.onLanguagesChanged(this.source, this.target);
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        langPanel.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        int panelH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        LayoutParams plp = new LayoutParams(LayoutParams.MATCH_PARENT, panelH, Gravity.TOP);
        plp.setMargins(dp(12), dp(100), dp(12), 0);
        addView(langPanel, plp);

        // ---- panel daftar teks
        listPanel = new ScrollView(ctx);
        listPanel.setBackground(rounded(0xF7FFFFFF, dp(16)));
        listPanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        listPanel.setVisibility(GONE);
        listContent = new LinearLayout(ctx);
        listContent.setOrientation(LinearLayout.VERTICAL);
        listPanel.addView(listContent);
        LayoutParams llp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        llp.setMargins(dp(12), dp(100), dp(12), dp(140));
        addView(listPanel, llp);

        // ---- tombol bawah
        bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackground(rounded(0xE6202124, dp(26)));
        bottom.setPadding(dp(4), dp(4), dp(4), dp(4));
        bottom.addView(button("Tutup", v -> actions.onClose()));
        bottom.addView(button("Daftar", v -> toggle(listPanel)));
        bottom.addView(button("Salin", v -> actions.onCopy(allTranslations())));
        bottom.addView(button("Lens", v -> actions.onLens()));
        addView(bottom, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            LayoutParams c = (LayoutParams) chip.getLayoutParams();
            c.topMargin = bars.top + dp(8);
            chip.setLayoutParams(c);
            LayoutParams p = (LayoutParams) langPanel.getLayoutParams();
            p.topMargin = bars.top + dp(56);
            langPanel.setLayoutParams(p);
            LayoutParams l = (LayoutParams) listPanel.getLayoutParams();
            l.topMargin = bars.top + dp(56);
            l.bottomMargin = bars.bottom + dp(84);
            listPanel.setLayoutParams(l);
            LayoutParams b = (LayoutParams) bottom.getLayoutParams();
            b.bottomMargin = bars.bottom + dp(16);
            bottom.setLayoutParams(b);
            return insets;
        });
        updateChip();
    }

    void setResult(TranslatePipeline.Result r) {
        busy = false;
        detected = r.detected;
        groups = new ArrayList<>(r.groups);
        Collections.sort(groups, (a, b) -> {
            int c = Integer.compare(a.bounds.top / 40, b.bounds.top / 40);
            return c != 0 ? c : (a.vertical ? Integer.compare(b.bounds.left, a.bounds.left)
                    : Integer.compare(a.bounds.left, b.bounds.left));
        });
        layer.prepare(groups);
        listContent.removeAllViews();
        for (Group g : groups) {
            if (g.ruby || g.translated == null) continue;
            TextView orig = new TextView(getContext());
            orig.setText(g.text);
            orig.setTextColor(0xFF5F6368);
            orig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            listContent.addView(orig);
            TextView tr = new TextView(getContext());
            tr.setText(g.translated);
            tr.setTextColor(0xFF202124);
            tr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tr.setTypeface(Typeface.DEFAULT_BOLD);
            tr.setPadding(0, dp(2), 0, dp(14));
            listContent.addView(tr);
        }
        updateChip();
    }

    void setBusy(boolean b) {
        busy = b;
        updateChip();
    }

    private void updateChip() {
        String src = Langs.AUTO.equals(source)
                ? (detected != null ? "Otomatis · " + Langs.displayName(detected) : "Otomatis")
                : Langs.displayName(source);
        chip.setText(busy ? "Menerjemahkan…" : src + "  →  " + Langs.displayName(target) + "  ▾");
    }

    private void toggleLanguages() {
        toggle(langPanel);
    }

    private void toggle(View panel) {
        boolean show = panel.getVisibility() != VISIBLE;
        langPanel.setVisibility(GONE);
        listPanel.setVisibility(GONE);
        panel.setVisibility(show ? VISIBLE : GONE);
    }

    private boolean hidePanels() {
        boolean any = langPanel.getVisibility() == VISIBLE || listPanel.getVisibility() == VISIBLE;
        langPanel.setVisibility(GONE);
        listPanel.setVisibility(GONE);
        return any;
    }

    private String allTranslations() {
        StringBuilder sb = new StringBuilder();
        for (Group g : groups) {
            if (g.ruby || g.translated == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(g.translated);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ komponen kecil

    private interface OnPick { void pick(String code); }

    private View langColumn(String label, LangAdapter adapter, OnPick onPick) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(getContext());
        h.setText(label);
        h.setTextColor(0xFF1A73E8);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        h.setPadding(dp(8), dp(4), 0, dp(4));
        col.addView(h);
        ListView lv = new ListView(getContext());
        lv.setAdapter(adapter);
        lv.setDivider(null);
        lv.setOnItemClickListener((parent, view, position, id) -> onPick.pick(adapter.codes.get(position)));
        col.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        lv.post(() -> {
            int i = adapter.codes.indexOf(adapter.selected);
            if (i > 2) lv.setSelection(i - 2);
        });
        return col;
    }

    private final class LangAdapter extends BaseAdapter {
        final List<String> codes;
        String selected;

        LangAdapter(List<String> codes, String selected) {
            this.codes = codes;
            this.selected = selected;
        }

        void setSelected(String s) {
            selected = s;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return codes.size(); }
        @Override public Object getItem(int i) { return codes.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : new TextView(getContext());
            String code = codes.get(i);
            boolean sel = code.equals(selected);
            t.setText(Langs.AUTO.equals(code) ? "Otomatis (deteksi)" : Langs.displayName(code));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTextColor(sel ? 0xFF1A73E8 : 0xFF202124);
            t.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setBackground(sel ? rounded(0xFFE8F0FE, dp(8)) : null);
            t.setPadding(dp(10), dp(10), dp(10), dp(10));
            return t;
        }
    }

    private TextView button(String label, OnClickListener l) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setOnClickListener(l);
        return b;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ lapisan terjemahan

    private final class TranslationLayer extends View {
        private final List<Group> drawn = new ArrayList<>();
        private final List<StaticLayout> layouts = new ArrayList<>();
        private final List<RectF> areas = new ArrayList<>();
        private final int[] loc = new int[2];
        private final Paint patchPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Typeface face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private boolean showOriginal, holdTriggered;
        private final Runnable holdRunnable = () -> {
            holdTriggered = true;
            showOriginal = true;
            setChromeVisible(false);
            invalidate();
        };

        TranslationLayer(Context ctx) {
            super(ctx);
        }

        void prepare(List<Group> groups) {
            drawn.clear();
            layouts.clear();
            areas.clear();
            float minSize = sp(8);
            for (Group g : groups) {
                drawn.add(g);
                if (g.ruby || g.translated == null) {
                    layouts.add(null);
                    areas.add(null);
                    continue;
                }
                float t = Math.max(1, g.thickness);
                RectF area = new RectF(g.bounds);
                if (g.vertical) area.inset(-0.35f * t, -0.1f * t);
                else area.inset(-0.08f * t, -0.08f * t);
                if (area.left < 0) area.left = 0;
                if (area.top < 0) area.top = 0;

                TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(g.fg);
                p.setTypeface(face);
                Layout.Alignment align = g.centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
                int width = Math.max(1, Math.round(area.width()));
                float maxSize = Math.max(minSize, Math.min(sp(40), t * 0.8f));

                // Cari ukuran huruf terbesar yang muat di area teks asli.
                float bestSize = maxSize;
                if (build(g.translated, p, maxSize, width, align).getHeight() > area.height()) {
                    float lo = minSize, hi = maxSize;
                    bestSize = minSize;
                    for (int i = 0; i < 8; i++) {
                        float mid = (lo + hi) / 2f;
                        if (build(g.translated, p, mid, width, align).getHeight() <= area.height()) {
                            lo = mid;
                            bestSize = mid;
                        } else {
                            hi = mid;
                        }
                    }
                }
                // Paint dipakai saat menggambar, jadi bangun ulang dengan ukuran final.
                StaticLayout best = build(g.translated, p, bestSize, width, align);
                // Lingkaran tipis berwarna latar agar tetap terbaca di atas latar ramai.
                p.setShadowLayer(Math.max(dp(1.5f), p.getTextSize() * 0.15f), 0, 0, g.bg);
                layouts.add(best);
                areas.add(area);
            }
            invalidate();
        }

        private StaticLayout build(String text, TextPaint p, float size, int width, Layout.Alignment align) {
            p.setTextSize(size);
            return StaticLayout.Builder.obtain(text, 0, text.length(), p, width)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setLineSpacing(0, 0.95f)
                    .build();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (showOriginal) return;
            getLocationOnScreen(loc);
            canvas.save();
            canvas.translate(-loc[0], -loc[1]);
            for (Group g : drawn) {
                for (LayoutAnalyzer.Patch p : g.patches) {
                    canvas.drawBitmap(p.bmp, p.r.left, p.r.top, patchPaint);
                }
            }
            for (int i = 0; i < drawn.size(); i++) {
                StaticLayout layout = layouts.get(i);
                if (layout == null) continue;
                RectF a = areas.get(i);
                canvas.save();
                canvas.translate(a.left, a.top + Math.max(0, (a.height() - layout.getHeight()) / 2f));
                layout.draw(canvas);
                canvas.restore();
            }
            canvas.restore();
        }

        private void setChromeVisible(boolean v) {
            int vis = v ? VISIBLE : INVISIBLE;
            chip.setVisibility(vis);
            bottom.setVisibility(vis);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holdTriggered = false;
                    handler.postDelayed(holdRunnable, 250);
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(holdRunnable);
                    if (holdTriggered) {
                        showOriginal = false;
                        setChromeVisible(true);
                        invalidate();
                    } else if (!hidePanels()) {
                        actions.onClose();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(holdRunnable);
                    showOriginal = false;
                    setChromeVisible(true);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }
}
