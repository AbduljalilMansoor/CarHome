package com.carhome.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * يتيح الوصول إلى Car Home من أي شاشة في السيارة:
 *  - أيقونة عائمة قابلة للسحب (اضغط لفتح Car Home).
 *  - شريطان شفافان على حافتي الشاشة: اسحب منهما للداخل لفتح Car Home.
 * تحتاج صلاحية الظهور فوق التطبيقات (SYSTEM_ALERT_WINDOW) وتُمنح عبر ADB من صفحة التثبيت.
 */
public class OverlayService extends Service {
    private static final String CH = "carhome_overlay";

    private WindowManager wm;
    private SharedPreferences prefs;
    private View bubble, edgeL, edgeR;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** يشغّل الخدمة أو يوقفها حسب الإعدادات والصلاحية. */
    static void sync(Context c) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        boolean want = p.getBoolean(MainActivity.KEY_BUBBLE, true) || p.getBoolean(MainActivity.KEY_EDGE, false);
        Intent i = new Intent(c, OverlayService.class);
        if (want && Settings.canDrawOverlays(c)) c.startForegroundService(i);
        else c.stopService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Car Home", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Car Home").build();
        startForeground(41, n);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        removeViews();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (prefs.getBoolean(MainActivity.KEY_BUBBLE, true)) addBubble();
        if (prefs.getBoolean(MainActivity.KEY_EDGE, false)) {
            edgeL = addEdge(Gravity.START);
            edgeR = addEdge(Gravity.END);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeViews();
    }

    private void removeViews() {
        for (View v : new View[]{bubble, edgeL, edgeR}) {
            if (v != null) { try { wm.removeView(v); } catch (Exception ignored) { } }
        }
        bubble = edgeL = edgeR = null;
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        return new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    private void openHome() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void addBubble() {
        final TextView t = new TextView(this);
        t.setText("⌂");
        t.setTextSize(30);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xDD1E88E5);
        g.setStroke(dp(2), 0xFFFFFFFF);
        t.setBackground(g);

        final WindowManager.LayoutParams lp = overlayParams(dp(64), dp(64));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("bx", 0);
        lp.y = prefs.getInt("by", dp(200));

        t.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved, longFired;
            final Runnable longPress = () -> {
                if (!moved) { longFired = true; WindowLauncher.closeAll(OverlayService.this); }
            };

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = lp.x; startY = lp.y; moved = false; longFired = false;
                        handler.postDelayed(longPress, 800);   // ضغط مطوّل = إغلاق كل النوافذ المفتوحة
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - downX, my = e.getRawY() - downY;
                        if (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8)) { moved = true; handler.removeCallbacks(longPress); }
                        if (moved) {
                            lp.x = (int) (startX + mx);
                            lp.y = (int) (startY + my);
                            wm.updateViewLayout(t, lp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPress);
                        if (moved) prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply();
                        else if (!longFired) openHome();
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(t, lp);
        bubble = t;
    }

    private View addEdge(final int gravity) {
        View v = new View(this);
        v.setBackgroundColor(0x01000000);   // شبه شفاف حتى يستقبل اللمس
        WindowManager.LayoutParams lp = overlayParams(dp(22), WindowManager.LayoutParams.MATCH_PARENT);
        lp.gravity = gravity | Gravity.TOP;
        v.setOnTouchListener(new View.OnTouchListener() {
            float x0;
            boolean fired;

            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); fired = false; return true;
                    case MotionEvent.ACTION_MOVE: {
                        float d = e.getRawX() - x0;
                        float inward = gravity == Gravity.START ? d : -d;
                        if (!fired && inward > dp(70)) { fired = true; openHome(); }
                        return true;
                    }
                    default: return true;
                }
            }
        });
        wm.addView(v, lp);
        return v;
    }
}
