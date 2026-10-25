package id.terjemahlayar;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Layanan aksesibilitas dengan tombol mengambang. Saat diketuk:
 * - mode Google: menekan lama bar navigasi (membuka Circle to Search) lalu menekan "Terjemahkan" otomatis,
 *   dengan cadangan Google Lens bila Circle to Search tidak terbuka;
 * - mode Lens: gambar layar dibuka di Google Lens lalu "Terjemahkan" ditekan otomatis;
 * - mode Offline: terjemahan di dalam HP (ML Kit) ditampilkan di atas layar.
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

    static final String GOOGLE_PKG = "com.google.android.googlequicksearchbox";
    /** Lama tekan bar navigasi. Pengguna tidak perlu menahan — aplikasi yang menekannya. */
    private static final long LONG_PRESS_MS = 1000;

    // Pemantauan jendela Google (Circle to Search / Lens)
    private final Runnable poll = this::pollGoogle;
    private boolean watchFromCts, seenGoogle, clicked;
    private long watchStart, lastSeen;
    /** Jendela Google yang sudah terbuka sebelum tombol diketuk — diabaikan. */
    private final java.util.Set<Integer> preGoogle = new java.util.HashSet<>();

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
        main.removeCallbacks(poll);
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
        snapshotGoogleWindows();
        // Sembunyikan tombol agar tidak ikut tertangkap di gambar layar.
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        String mode = Prefs.mode(this);
        if (Prefs.MODE_GOOGLE.equals(mode)) main.postDelayed(this::triggerCircleToSearch, 120);
        else if (Prefs.MODE_LENS.equals(mode)) main.postDelayed(() -> capture(true), 120);
        else main.postDelayed(() -> capture(false), 120);
    }

    // ---------------------------------------------------------------- mode Google

    /** Tekan lama bar navigasi (sama seperti jari Anda) untuk membuka Circle to Search. */
    private void triggerCircleToSearch() {
        WindowMetrics m = wm.getCurrentWindowMetrics();
        Rect b = m.getBounds();
        Insets nav = m.getWindowInsets().getInsets(WindowInsets.Type.navigationBars());
        float x = b.width() / 2f;
        float y = b.height() - Math.max(nav.bottom / 2f, dp(8));
        y = Math.min(y, b.height() - 2);
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MS))
                .build();
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                startWatch(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                capture(true);
            }
        }, main);
        if (!ok) capture(true);
    }

    private void startWatch(boolean fromCts) {
        main.removeCallbacks(poll);
        watchFromCts = fromCts;
        watchStart = SystemClock.uptimeMillis();
        seenGoogle = false;
        clicked = false;
        main.postDelayed(poll, 250);
    }

    /** Pantau jendela Google: tekan "Terjemahkan" begitu muncul, tampilkan tombol lagi setelah ditutup. */
    private void pollGoogle() {
        long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = googleRoot();
        if (root != null) {
            seenGoogle = true;
            lastSeen = now;
            if (!clicked && now - watchStart < 8000) clicked = clickTranslate(root);
        } else if (!seenGoogle) {
            if (now - watchStart > (watchFromCts ? 2500 : 8000)) {
                if (watchFromCts) {
                    // Circle to Search tidak terbuka di HP ini → pakai Google Lens.
                    main.removeCallbacks(poll);
                    showToast("Circle to Search tidak terbuka, memakai Google Lens…", false);
                    capture(true);
                } else {
                    endWatch();
                }
                return;
            }
        } else if (now - lastSeen > 1000) {
            endWatch();
            return;
        }
        main.postDelayed(poll, clicked ? 700 : 250);
    }

    private void endWatch() {
        main.removeCallbacks(poll);
        busy = false;
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
    }

    private void snapshotGoogleWindows() {
        preGoogle.clear();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && GOOGLE_PKG.contentEquals(r.getPackageName())) preGoogle.add(w.getId());
            }
        } catch (RuntimeException ignored) { }
    }

    private AccessibilityNodeInfo googleRoot() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (preGoogle.contains(w.getId())) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && GOOGLE_PKG.contentEquals(r.getPackageName())) return r;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** Cari tombol "Terjemahkan"/"Translate" di jendela Google lalu tekan. */
    private boolean clickTranslate(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            String id = n.getViewIdResourceName();
            boolean match = isTranslateLabel(n.getText()) || isTranslateLabel(n.getContentDescription())
                    || (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("translate"));
            if (match && n.isVisibleToUser() && press(n)) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return false;
    }

    private static boolean isTranslateLabel(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty() || s.length() > 24) return false;
        return s.equals("terjemahkan") || s.equals("terjemah") || s.equals("terjemahan")
                || s.equals("translate") || s.equals("translation")
                || s.startsWith("terjemahkan ") || s.startsWith("translate ");
    }

    private boolean press(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int up = 0; c != null && !c.isClickable() && up < 5; up++) c = c.getParent();
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    // ---------------------------------------------------------------- gambar layar (Lens / Offline)

    private void capture(boolean forLens) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer hb = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                Bitmap bmp = hw != null ? hw.copy(Bitmap.Config.ARGB_8888, false) : null;
                hb.close();
                if (bmp == null) {
                    if (bubble != null) bubble.setVisibility(View.VISIBLE);
                    fail("Gagal memproses gambar layar.");
                    return;
                }
                lastShot = bmp;
                if (forLens) {
                    // Tombol tetap disembunyikan selama Google Lens terbuka.
                    openInLens();
                    startWatch(false);
                    return;
                }
                if (bubble != null) bubble.setVisibility(View.VISIBLE);
                setBubbleBusy(true);
                runPipeline(bmp);
            }

            @Override
            public void onFailure(int errorCode) {
                main.removeCallbacks(poll);
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
                busy = true;
                snapshotGoogleWindows();
                if (bubble != null) bubble.setVisibility(View.INVISIBLE);
                openInLens();
                startWatch(false);
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
        Intent toGoogle = new Intent(send).setPackage(GOOGLE_PKG);
        // Aplikasi Google punya beberapa tujuan berbagi; pilih yang Lens agar tidak muncul pilihan.
        try {
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(toGoogle, 0)) {
                if (ri.activityInfo != null && ri.activityInfo.name.toLowerCase(java.util.Locale.ROOT).contains("lens")) {
                    toGoogle.setClassName(GOOGLE_PKG, ri.activityInfo.name);
                    break;
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            startActivity(toGoogle);
        } catch (ActivityNotFoundException e) {
            Intent chooser = Intent.createChooser(send, "Buka gambar layar dengan");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(chooser); } catch (Exception ignored) { }
        }
    }
}
