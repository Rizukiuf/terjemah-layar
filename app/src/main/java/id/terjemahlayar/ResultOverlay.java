package id.terjemahlayar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lapisan layar penuh yang menampilkan terjemahan tepat di atas teks aslinya.
 * Ketuk = tutup. Tahan = intip teks asli. Tombol bawah: Tutup · Daftar · Salin · Lens.
 */
@SuppressLint("ViewConstructor")
final class ResultOverlay extends FrameLayout {

    interface Actions {
        void onClose();
        void onCopy(String allText);
        void onLens();
    }

    private final Actions actions;
    private final TranslationLayer layer;
    private final ScrollView listPanel;
    private final LinearLayout listContent;
    private final LinearLayout bottom;
    private List<TranslatePipeline.Item> items = new ArrayList<>();

    ResultOverlay(Context ctx, Actions actions) {
        super(ctx);
        this.actions = actions;

        layer = new TranslationLayer(ctx);
        addView(layer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Panel daftar teks (asli → terjemahan), berguna bila kotak terjemahan terlalu kecil.
        listPanel = new ScrollView(ctx);
        listPanel.setBackground(rounded(0xF2FFFFFF, dp(16)));
        listPanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        listPanel.setVisibility(GONE);
        listContent = new LinearLayout(ctx);
        listContent.setOrientation(LinearLayout.VERTICAL);
        listPanel.addView(listContent);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(12), dp(48), dp(12), dp(140));
        addView(listPanel, lp);

        // Bar tombol di bawah
        bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView hint = new TextView(ctx);
        hint.setText("Ketuk = tutup  ·  Tahan = lihat teks asli");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        hint.setPadding(0, 0, 0, dp(6));
        bottom.addView(hint);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(rounded(0xE6202124, dp(28)));
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.addView(button("Tutup", v -> actions.onClose()));
        bar.addView(button("Daftar", v -> toggleList()));
        bar.addView(button("Salin", v -> actions.onCopy(allTranslations())));
        bar.addView(button("Lens", v -> actions.onLens()));
        bottom.addView(bar);

        LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        addView(bottom, blp);

        // Jaga agar tombol tidak tertutup bar navigasi.
        setOnApplyWindowInsetsListener((v, insets) -> {
            int nav = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            LayoutParams p = (LayoutParams) bottom.getLayoutParams();
            p.bottomMargin = nav + dp(20);
            bottom.setLayoutParams(p);
            return insets;
        });
    }

    void setItems(List<TranslatePipeline.Item> items) {
        this.items = items;
        layer.prepare(items);
        listContent.removeAllViews();
        for (TranslatePipeline.Item it : items) {
            TextView orig = new TextView(getContext());
            orig.setText(it.original);
            orig.setTextColor(0xFF5F6368);
            orig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            listContent.addView(orig);
            TextView tr = new TextView(getContext());
            tr.setText(it.translated);
            tr.setTextColor(0xFF202124);
            tr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tr.setTypeface(Typeface.DEFAULT_BOLD);
            tr.setPadding(0, dp(2), 0, dp(14));
            listContent.addView(tr);
        }
    }

    private void toggleList() {
        listPanel.setVisibility(listPanel.getVisibility() == VISIBLE ? GONE : VISIBLE);
    }

    private String allTranslations() {
        StringBuilder sb = new StringBuilder();
        for (TranslatePipeline.Item it : items) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(it.translated);
        }
        return sb.toString();
    }

    private TextView button(String label, OnClickListener l) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(rounded(0x00000000, dp(22)));
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

    /** Menggambar kotak terjemahan di posisi teks asli. */
    private final class TranslationLayer extends View {
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimPaint = new Paint();
        private final List<StaticLayout> layouts = new ArrayList<>();
        private final List<TranslatePipeline.Item> drawn = new ArrayList<>();
        private final int[] loc = new int[2];
        private final RectF tmp = new RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean showOriginal;
        private boolean holdTriggered;
        private final Runnable holdRunnable = () -> {
            holdTriggered = true;
            showOriginal = true;
            bottom.setVisibility(INVISIBLE);
            invalidate();
        };

        TranslationLayer(Context ctx) {
            super(ctx);
            textPaint.setColor(0xFF202124);
            bgPaint.setColor(0xF5FFFDF5);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setColor(0x661A73E8);
            dimPaint.setColor(0x14000000);
        }

        void prepare(List<TranslatePipeline.Item> items) {
            layouts.clear();
            drawn.clear();
            float minSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9, getResources().getDisplayMetrics());
            float maxSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22, getResources().getDisplayMetrics());
            for (TranslatePipeline.Item it : items) {
                Rect b = it.box;
                int width = Math.max(b.width(), dp(48));
                float boxH = Math.max(b.height(), dp(12));
                float size = Math.max(minSize, Math.min(maxSize, boxH / it.lineCount * 0.78f));
                StaticLayout layout;
                while (true) {
                    textPaint.setTextSize(size);
                    layout = StaticLayout.Builder
                            .obtain(it.translated, 0, it.translated.length(), textPaint, width)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setIncludePad(false)
                            .build();
                    if (layout.getHeight() <= boxH || size <= minSize) break;
                    size = Math.max(minSize, size * 0.9f);
                }
                layouts.add(layout);
                drawn.add(it);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPaint(dimPaint);
            if (showOriginal) return;
            // Koordinat OCR = koordinat layar. Koreksi jika jendela ini tidak dimulai tepat di (0,0).
            getLocationOnScreen(loc);
            canvas.save();
            canvas.translate(-loc[0], -loc[1]);
            float pad = dp(3);
            float radius = dp(6);
            for (int i = 0; i < drawn.size(); i++) {
                TranslatePipeline.Item it = drawn.get(i);
                StaticLayout layout = layouts.get(i);
                Rect b = it.box;
                float w = layout.getWidth();
                float h = Math.max(b.height(), layout.getHeight());
                tmp.set(b.left - pad, b.top - pad, b.left + w + pad, b.top + h + pad);
                canvas.drawRoundRect(tmp, radius, radius, bgPaint);
                canvas.drawRoundRect(tmp, radius, radius, borderPaint);
                canvas.save();
                canvas.translate(b.left, b.top + Math.max(0, (b.height() - layout.getHeight()) / 2f));
                layout.draw(canvas);
                canvas.restore();
            }
            canvas.restore();
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
                        bottom.setVisibility(VISIBLE);
                        invalidate();
                    } else {
                        actions.onClose();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(holdRunnable);
                    showOriginal = false;
                    bottom.setVisibility(VISIBLE);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }
}
