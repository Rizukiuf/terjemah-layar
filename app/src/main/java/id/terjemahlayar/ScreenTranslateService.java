package id.terjemahlayar;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Layanan aksesibilitas: menampilkan tombol mengambang, mengambil gambar layar saat tombol diketuk,
 * lalu menampilkan hasil terjemahan di atas layar.
 */
public class ScreenTranslateService extends AccessibilityService {

    private static ScreenTranslateService instance;

    static ScreenTranslateService get() { return instance; }

    private WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TranslatePipeline pipeline;

    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private ResultOverlay overlay;
    private Bitmap lastShot;
    private boolean busy;
    private Toast toast;

    private AccessibilityButtonController.AccessibilityButtonCallback a11yButtonCallback;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = getSystemService(WindowManager.class);
        pipeline = new TranslatePipeline();
        pipeline.warmUp();
        applyPrefs();

        // Tombol aksesibilitas sistem (opsional) juga memicu terjemahan.
        a11yButtonCallback = new AccessibilityButtonController.AccessibilityButtonCallback() {
            @Override
            public void onClicked(AccessibilityButtonController controller) {
                translateScreen();
            }
        };
        getAccessibilityButtonController().registerAccessibilityButtonCallback(a11yButtonCallback);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Tidak dipakai.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    private void cleanup() {
        if (instance == this) instance = null;
        removeOverlay();
        removeBubble();
        if (a11yButtonCallback != null) {
            getAccessibilityButtonController().unregisterAccessibilityButtonCallback(a11yButtonCallback);
            a11yButtonCallback = null;
        }
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        main.removeCallbacksAndMessages(null);
    }

    /** Dipanggil saat pengaturan berubah. */
    void applyPrefs() {
        if (Prefs.bubbleEnabled(this)) showBubble();
        else removeBubble();
    }

    // ---------------------------------------------------------------- tombol mengambang

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        if (bubble != null) return;
        int size = dp(48);
        bubble = new TextView(this);
        bubble.setText("文A");
        bubble.setTextColor(Color.WHITE);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC1A73E8);
        bg.setStroke(dp(2), 0x99FFFFFF);
        bubble.setBackground(bg);
        bubble.setElevation(dp(4));

        bubbleParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        Rect screen = wm.getCurrentWindowMetrics().getBounds();
        int x = Prefs.bubbleX(this);
        int y = Prefs.bubbleY(this);
        bubbleParams.x = (x < 0) ? screen.width() - size - dp(4) : Math.min(x, screen.width() - size);
        bubbleParams.y = (y < 0) ? screen.height() / 2 : Math.min(y, screen.height() - size);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            final int slop = ViewConfiguration.get(ScreenTranslateService.this).getScaledTouchSlop();
            float downX, downY;
            int startX, startY;
            boolean dragging, longPressed;
            final Runnable longPress = () -> {
                longPressed = true;
                openSettings();
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        dragging = false;
                        longPressed = false;
                        main.postDelayed(longPress, 700);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            dragging = true;
                            main.removeCallbacks(longPress);
                        }
                        if (dragging) {
                            bubbleParams.x = Math.round(startX + dx);
                            bubbleParams.y = Math.round(startY + dy);
                            wm.updateViewLayout(bubble, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (dragging) {
                            snapBubbleToEdge();
                        } else if (!longPressed) {
                            translateScreen();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        return true;
                }
                return false;
            }
        });

        wm.addView(bubble, bubbleParams);
    }

    private void snapBubbleToEdge() {
        Rect screen = wm.getCurrentWindowMetrics().getBounds();
        int size = bubbleParams.width;
        int center = bubbleParams.x + size / 2;
        bubbleParams.x = center < screen.width() / 2 ? dp(4) : screen.width() - size - dp(4);
        bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, screen.height() - size));
        wm.updateViewLayout(bubble, bubbleParams);
        Prefs.setBubblePos(this, bubbleParams.x, bubbleParams.y);
    }

    private void removeBubble() {
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
            bubble = null;
        }
    }

    private void setBubbleBusy(boolean b) {
        if (bubble == null) return;
        bubble.setText(b ? "…" : "文A");
        bubble.setAlpha(b ? 0.6f : 1f);
    }

    private void openSettings() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- alur terjemahan

    /** Dari tile Pengaturan Cepat: tutup panel notifikasi dulu, baru ambil gambar layar. */
    void translateFromTile() {
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        main.postDelayed(this::translateScreen, 550);
    }

    void translateScreen() {
        if (busy) return;
        if (overlay != null) { // ketuk lagi saat hasil tampil = tutup
            removeOverlay();
            return;
        }
        busy = true;
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        // Beri waktu satu-dua frame agar tombol benar-benar hilang dari gambar layar.
        main.postDelayed(this::capture, 120);
    }

    private void capture() {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                if (bubble != null) bubble.setVisibility(View.VISIBLE);
                HardwareBuffer hb = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                Bitmap bmp = hw != null ? hw.copy(Bitmap.Config.ARGB_8888, false) : null;
                hb.close();
                if (bmp == null) {
                    fail("Gagal memproses gambar layar.");
                    return;
                }
                lastShot = bmp;
                setBubbleBusy(true);
                runPipeline(bmp);
            }

            @Override
            public void onFailure(int errorCode) {
                if (bubble != null) bubble.setVisibility(View.VISIBLE);
                if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    fail("Aplikasi ini melarang tangkapan layar (mis. aplikasi bank), jadi tidak bisa diterjemahkan.");
                } else if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    fail("Terlalu cepat, coba ketuk lagi.");
                } else {
                    fail("Gagal mengambil gambar layar (kode " + errorCode + ").");
                }
            }
        });
    }

    private void runPipeline(Bitmap bmp) {
        if (pipeline == null) { busy = false; return; }
        pipeline.run(bmp, Prefs.source(this), Prefs.target(this), new TranslatePipeline.Callback() {
            @Override
            public void onStatus(String message) {
                showToast(message, true);
            }

            @Override
            public void onDone(TranslatePipeline.Result result) {
                busy = false;
                setBubbleBusy(false);
                cancelToast();
                if (overlay != null) overlay.setResult(result);
                else showOverlay(result);
            }

            @Override
            public void onError(String message) {
                if (overlay != null) overlay.setBusy(false);
                fail(message);
            }
        });
    }

    /** Pengguna mengganti bahasa di layar hasil: terjemahkan ulang gambar layar yang sama. */
    private void retranslate(String source, String target) {
        Prefs.setSource(this, source);
        Prefs.setTarget(this, target);
        if (lastShot == null) return;
        if (overlay != null) overlay.setBusy(true);
        busy = true;
        setBubbleBusy(true);
        runPipeline(lastShot);
    }

    private void fail(String message) {
        busy = false;
        setBubbleBusy(false);
        showToast(message, true);
    }

    private void showToast(String msg, boolean longer) {
        cancelToast();
        toast = Toast.makeText(this, msg, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.show();
    }

    private void cancelToast() {
        if (toast != null) { toast.cancel(); toast = null; }
    }

    // ---------------------------------------------------------------- lapisan hasil

    private void showOverlay(TranslatePipeline.Result result) {
        removeOverlay();
        overlay = new ResultOverlay(this, Prefs.source(this), Prefs.target(this), new ResultOverlay.Actions() {
            @Override
            public void onLanguagesChanged(String source, String target) {
                retranslate(source, target);
            }

            @Override
            public void onClose() {
                removeOverlay();
            }

            @Override
            public void onCopy(String allText) {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText("Terjemahan", allText));
                showToast("Terjemahan disalin", false);
            }

            @Override
            public void onLens() {
                removeOverlay();
                openInLens();
            }
        });
        overlay.setResult(result);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        wm.addView(overlay, p);

        // Pastikan tombol mengambang tetap di atas lapisan hasil.
        if (bubble != null) {
            try {
                wm.removeView(bubble);
                wm.addView(bubble, bubbleParams);
            } catch (Exception ignored) { }
        }
    }

    private void removeOverlay() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) { }
            overlay = null;
        }
    }

    /** Kirim gambar layar ke Google Lens/Google untuk bertanya tentang isi layar. */
    private void openInLens() {
        final Bitmap shot = lastShot;
        if (shot == null) return;
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "shots");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, "layar.jpg");
                try (FileOutputStream os = new FileOutputStream(f)) {
                    shot.compress(Bitmap.CompressFormat.JPEG, 92, os);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                main.post(() -> launchShare(uri));
            } catch (Exception e) {
                main.post(() -> showToast("Gagal menyiapkan gambar: " + e.getMessage(), true));
            }
        }).start();
    }

    private void launchShare(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("layar", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent toGoogle = new Intent(send).setPackage("com.google.android.googlequicksearchbox");
        try {
            startActivity(toGoogle);
        } catch (ActivityNotFoundException e) {
            Intent chooser = Intent.createChooser(send, "Buka gambar layar dengan");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(chooser); } catch (Exception ignored) { }
        }
    }
}
