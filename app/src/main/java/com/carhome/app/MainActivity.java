package com.carhome.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Car Home — الشاشة الرئيسية، أربعة أقسام:
 *  1) شريط علوي: بيانات السيارة الحية والساعة.
 *  2) منطقة يسار + 3) منطقة يمين (50:50 ثابتة): في كل منهما تطبيق مختلف، ويمكن تغييره في أي وقت.
 *  4) شريط سفلي: اختيار الشاشة + 5 خانات مفضلة (+) + زر «التطبيقات» + الودجات + الإعدادات.
 * ويمكن فتحه من أي شاشة بالأيقونة العائمة أو السحب من الحافة (OverlayService).
 */
public class MainActivity extends Activity {
    static final String PREFS = "carhome";
    static final String KEY_PINNED = "pinned", KEY_DISPLAY = "display";
    static final String KEY_BUBBLE = "bubble", KEY_EDGE = "edge";
    static final String KEY_Z0 = "z0", KEY_Z1 = "z1", KEY_ZD0 = "zd0", KEY_ZD1 = "zd1";
    static final String KEY_MT = "mt", KEY_MB = "mb", KEY_WINDOW = "window";
    static final String NEW_TRIP_PKG = "com.newtrip.app";
    static final int FAV_SLOTS = 5;
    private static final int[] MARGIN_STEPS = {0, 30, 60, 90, 120, 160};
    private static final String[] ZONE_NAMES = {"اليسار", "اليمين"};

    // وضع صفحة التطبيقات
    private static final int PICK_NONE = 0, PICK_ZONE = 1, PICK_FAV = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // بيانات السيارة
    private CarBridge car;
    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean polling;

    // الحالة
    private final String[] zoneContent = {"", ""};
    private final int[] zoneDisplay = {0, 0};
    private int activeZone = 0;
    private int selectedDisplay = WindowLauncher.FRONT;
    private long lastApply;
    private int pickMode = PICK_NONE, pickIndex = -1;

    // واجهة
    private FrameLayout rootFrame, appsPage;
    private LinearLayout main, zonesRow, dockRow, appsCol;
    private TopBar topBar;
    private final LinearLayout[] zoneCard = new LinearLayout[2];
    private final FrameLayout[] zoneBody = new FrameLayout[2];
    private final TextView[] zoneTitle = new TextView[2];
    private final ImageView[] zoneIcon = new ImageView[2];
    private final TextView[] zoneChange = new TextView[2];
    private final TextView[] zoneClose = new TextView[2];
    private TextView displayBtn, appsTitle, appsHint;
    private GridLayout grid;

    private List<AppItem> apps = new ArrayList<>();
    private boolean appsDirty = true;
    private Updater.Info pendingUpdate;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            topBar.render(car.live);
            ui.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { appsDirty = true; loadApps(); }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    // ======================= دورة الحياة =======================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Updater.init(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(KEY_PINNED)) prefs.edit().putString(KEY_PINNED, NEW_TRIP_PKG + ",,,,").apply();
        loadState();
        car = new CarBridge();

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.BG);
        rootFrame.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        topBar = new TopBar(this);
        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.addView(topBar.view, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        zlp.topMargin = dp(8);
        main.addView(buildZones(), zlp);
        main.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, -2));
        applyMargins();
        rootFrame.addView(main, new FrameLayout.LayoutParams(-1, -1));

        appsPage = buildAppsPage();
        appsPage.setVisibility(View.GONE);
        rootFrame.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        renderZones();
        renderDock();
        updateDisplayButton();
        loadApps();
        checkUpdateQuietly();
        try { OverlayService.sync(this); } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appsDirty) loadApps();
        startPolling();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        stopPolling();
        car.release();
    }

    /** زر Home أو الأيقونة العائمة: أغلق القوائم وأعد إظهار تطبيقات الجانبين. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideApps();
        applyZones();
    }

    @Override
    public void onBackPressed() {
        if (appsPage.getVisibility() == View.VISIBLE) hideApps();
    }

    // ======================= الحالة المحفوظة =======================
    private void loadState() {
        selectedDisplay = prefs.getInt(KEY_DISPLAY, WindowLauncher.FRONT);
        if (selectedDisplay != WindowLauncher.FRONT && selectedDisplay != WindowLauncher.REAR) selectedDisplay = WindowLauncher.FRONT;
        zoneContent[0] = cleanZone(prefs.getString(KEY_Z0, ""));
        zoneContent[1] = cleanZone(prefs.getString(KEY_Z1, ""));
        zoneDisplay[0] = prefs.getInt(KEY_ZD0, 0);
        zoneDisplay[1] = prefs.getInt(KEY_ZD1, 0);
    }

    /** نسخة سابقة كانت تحفظ "dash" للوحة السيارة (انتقلت الآن إلى الشريط العلوي). */
    private static String cleanZone(String s) { return s == null || s.equals("dash") ? "" : s; }

    private void saveZones() {
        prefs.edit().putString(KEY_Z0, zoneContent[0]).putString(KEY_Z1, zoneContent[1])
                .putInt(KEY_ZD0, zoneDisplay[0]).putInt(KEY_ZD1, zoneDisplay[1]).apply();
    }

    private void applyMargins() {
        int mt = dp(prefs.getInt(KEY_MT, 0)), mb = dp(prefs.getInt(KEY_MB, 0));
        main.setPadding(dp(12), dp(8) + mt, dp(12), dp(10) + mb);
        if (appsCol != null) appsCol.setPadding(dp(20), dp(20) + mt, dp(20), dp(20) + mb);
    }

    // ======================= قراءة السيارة =======================
    private void startPolling() {
        if (polling) return;
        polling = true;
        pollThread = new HandlerThread("carhome-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        final Context app = getApplicationContext();
        pollHandler.post(new Runnable() {
            @Override public void run() {
                if (!polling) return;
                try {
                    car.connect(app);
                    car.poll();
                } catch (Throwable ignored) { }
                if (polling) pollHandler.postDelayed(this, 1000);
            }
        });
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) { pollThread.quitSafely(); pollThread = null; }
    }

    // ======================= المنطقتان (50:50 ثابتة) =======================
    private View buildZones() {
        zonesRow = new LinearLayout(this);
        zonesRow.setOrientation(LinearLayout.HORIZONTAL);
        zonesRow.setBaselineAligned(false);
        for (int z = 0; z < 2; z++) {
            zoneCard[z] = buildZone(z);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            zonesRow.addView(zoneCard[z], lp);
        }
        return zonesRow;
    }

    private LinearLayout buildZone(final int z) {
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        zoneIcon[z] = new ImageView(this);
        head.addView(zoneIcon[z], new LinearLayout.LayoutParams(dp(30), dp(30)));
        zoneTitle[z] = Ui.text(this, "", 18, Ui.TEXT, true);
        zoneTitle[z].setPadding(dp(8), 0, 0, 0);
        zoneTitle[z].setSingleLine(true);
        head.addView(zoneTitle[z], new LinearLayout.LayoutParams(0, -2, 1f));

        zoneChange[z] = headButton("⇄  تغيير", Ui.EV);
        zoneChange[z].setOnClickListener(v -> startPick(PICK_ZONE, z));
        head.addView(zoneChange[z]);
        zoneClose[z] = headButton("✕", Ui.MUTED);
        zoneClose[z].setOnClickListener(v -> { zoneContent[z] = ""; saveZones(); renderZone(z); });
        head.addView(zoneClose[z]);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        zoneBody[z] = new FrameLayout(this);
        zoneBody[z].setBackground(Ui.round(Ui.BG, dp(12)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(8);
        card.addView(zoneBody[z], blp);
        return card;
    }

    private TextView headButton(String label, int color) {
        TextView t = Ui.text(this, label, 17, color, true);
        t.setPadding(dp(14), dp(8), dp(10), dp(8));
        return t;
    }

    private void setActive(int z) {
        activeZone = z;
        highlightZones();
    }

    private void highlightZones() {
        for (int i = 0; i < 2; i++) {
            GradientDrawable g = Ui.round(Ui.CARD, dp(16));
            g.setStroke(dp(3), Ui.CARD);
            zoneCard[i].setBackground(g);
        }
    }

    private void renderZones() { renderZone(0); renderZone(1); }

    private void renderZone(final int z) {
        zoneBody[z].removeAllViews();
        zoneIcon[z].setImageDrawable(null);
        highlightZones();
        String c = zoneContent[z];
        boolean empty = c.isEmpty();
        zoneChange[z].setVisibility(empty ? View.GONE : View.VISIBLE);
        zoneClose[z].setVisibility(empty ? View.GONE : View.VISIBLE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(16), dp(8), dp(16), dp(8));

        if (empty) {
            zoneTitle[z].setText("الجانب " + ZONE_NAMES[z]);
            col.addView(Ui.text(this, "＋", 56, Ui.MUTED, true));
            TextView t = Ui.text(this, "أضف تطبيقك المفضل", 20, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER);
            col.addView(t);
            TextView h = Ui.text(this, "اضغط هنا لاختيار تطبيق يعمل في هذا الجانب", 14, Ui.MUTED, false);
            h.setGravity(Gravity.CENTER);
            h.setPadding(0, dp(6), 0, 0);
            col.addView(h);
            col.setOnClickListener(v -> startPick(PICK_ZONE, z));
        } else {
            AppItem a = find(c);
            zoneTitle[z].setText(a == null ? c : a.label);
            if (a != null) {
                zoneIcon[z].setImageDrawable(a.icon);
                ImageView big = new ImageView(this);
                big.setImageDrawable(a.icon);
                col.addView(big, new LinearLayout.LayoutParams(dp(96), dp(96)));
            }
            boolean other = zoneDisplay[z] != WindowLauncher.FRONT;
            TextView t = Ui.text(this, other
                    ? "يعمل على " + WindowLauncher.displayLabel(zoneDisplay[z], null)
                    : "التطبيق يعمل في هذا الجانب\nاضغط لإعادة تشغيله هنا", 16, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, 0);
            col.addView(t);
            col.setOnClickListener(v -> launchZone(z));
        }
        zoneBody[z].addView(col, new FrameLayout.LayoutParams(-1, -1));
    }

    /** تعيين تطبيق لجانب وتشغيله على الشاشة المختارة. */
    private void assign(int z, AppItem a) {
        activeZone = z;
        zoneContent[z] = a.pkg;
        zoneDisplay[z] = selectedDisplay;
        saveZones();
        renderZone(z);
        launchZone(z);
    }

    private void launchZone(final int z) {
        String c = zoneContent[z];
        if (c.isEmpty()) return;
        final AppItem a = find(c);
        if (a == null) { toast("التطبيق غير مثبّت"); return; }
        final int d = zoneDisplay[z];
        zoneBody[z].post(() -> {
            Rect r = null;
            if (d == WindowLauncher.FRONT) {
                int[] loc = new int[2];
                zoneBody[z].getLocationOnScreen(loc);
                r = new Rect(loc[0], loc[1], loc[0] + zoneBody[z].getWidth(), loc[1] + zoneBody[z].getHeight());
            }
            String err = WindowLauncher.launch(this, a, d, r);
            if (err != null) toast("تعذر التشغيل: " + err);
        });
    }

    /** إعادة إظهار تطبيقات الجانبين (عند العودة إلى Car Home). */
    private void applyZones() {
        long now = System.currentTimeMillis();
        if (now - lastApply < 1500) return;
        lastApply = now;
        launchZone(0);
        launchZone(1);
    }

    // ======================= الشريط السفلي =======================
    private View buildBottomBar() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, 0);

        displayBtn = Ui.text(this, "", 16, Ui.TEXT, true);
        displayBtn.setBackground(Ui.round(Ui.CARD2, dp(14)));
        displayBtn.setPadding(dp(16), dp(16), dp(16), dp(16));
        displayBtn.setOnClickListener(v -> showDisplayDialog());
        bar.addView(displayBtn, new LinearLayout.LayoutParams(-2, -2));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setBackground(Ui.round(Ui.CARD, dp(18)));
        dockRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, -2, 1f);
        dlp.setMargins(dp(10), 0, dp(10), 0);
        bar.addView(dockRow, dlp);

        TextView gear = Ui.text(this, "⚙", 26, Ui.TEXT, true);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.round(Ui.CARD2, dp(14)));
        gear.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(62), dp(62));
        glp.rightMargin = dp(8);
        bar.addView(gear, glp);

        TextView widgets = Ui.text(this, "▦  WIDGETS", 15, Ui.TEXT, true);
        widgets.setGravity(Gravity.CENTER);
        widgets.setBackground(Ui.round(Ui.CARD2, dp(14)));
        widgets.setPadding(dp(16), dp(16), dp(16), dp(16));
        widgets.setOnClickListener(v -> showWidgets());
        bar.addView(widgets, new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    private void updateDisplayButton() {
        displayBtn.setText("▾  Display " + selectedDisplay + (selectedDisplay == WindowLauncher.FRONT ? " (Front)"
                : selectedDisplay == WindowLauncher.REAR ? " (Rear)" : ""));
    }

    private void showDisplayDialog() {
        final List<int[]> ds = WindowLauncher.displays(this);
        String[] labels = new String[ds.size()];
        int checked = 0;
        for (int i = 0; i < ds.size(); i++) {
            int id = ds.get(i)[0];
            labels[i] = WindowLauncher.displayLabel(id, null);
            if (id == selectedDisplay) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("الشاشة التي يعمل عليها التطبيق")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    selectedDisplay = ds.get(which)[0];
                    prefs.edit().putInt(KEY_DISPLAY, selectedDisplay).apply();
                    updateDisplayButton();
                    d.dismiss();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ---------- المفضلة: خمس خانات ثابتة ----------
    private String[] getFavs() {
        String[] f = new String[FAV_SLOTS];
        Arrays.fill(f, "");
        String[] p = prefs.getString(KEY_PINNED, "").split(",", -1);
        for (int i = 0; i < FAV_SLOTS && i < p.length; i++) f[i] = p[i];
        return f;
    }

    private void setFavs(String[] f) {
        prefs.edit().putString(KEY_PINNED, String.join(",", f)).apply();
        renderDock();
    }

    private void renderDock() {
        dockRow.removeAllViews();
        String[] fav = getFavs();
        for (int i = 0; i < FAV_SLOTS; i++) {
            final int slot = i;
            final AppItem a = fav[i].isEmpty() ? null : find(fav[i]);
            LinearLayout cell = dockCell(a == null ? null : a.icon, a == null ? "إضافة" : a.label);
            if (a == null) {
                cell.setOnClickListener(v -> startPick(PICK_FAV, slot));
            } else {
                cell.setOnClickListener(v -> openWindow(a));
                cell.setOnLongClickListener(v -> { favMenu(slot, a); return true; });
            }
            dockRow.addView(cell, dockLp());
        }
        // زر كل التطبيقات
        LinearLayout all = dockCell(null, "التطبيقات");
        ((TextView) ((LinearLayout) all).getChildAt(0)).setText("⊞");
        all.setOnClickListener(v -> startPick(PICK_NONE, -1));
        dockRow.addView(all, dockLp());
    }

    private LinearLayout.LayoutParams dockLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(8), 0, dp(8), 0);
        return lp;
    }

    /** خانة: أيقونة (أو علامة + إن لم يوجد) وتحتها اسم. الابن الأول دائماً الأيقونة. */
    private LinearLayout dockCell(Drawable icon, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(8), dp(2), dp(8), dp(2));
        cell.setClickable(true);
        View iconView;
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iconView = iv;
        } else {
            TextView plus = Ui.text(this, "＋", 28, Ui.MUTED, true);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.round(Ui.CARD2, dp(14)));
            iconView = plus;
        }
        cell.addView(iconView, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView t = Ui.text(this, label, 12, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setMaxWidth(dp(84));
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(t);
        return cell;
    }

    private void favMenu(final int slot, AppItem a) {
        String[] items = {"تغيير التطبيق", "إزالة من المفضلة"};
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            if (which == 0) {
                startPick(PICK_FAV, slot);
            } else {
                String[] f = getFavs();
                f[slot] = "";
                setFavs(f);
            }
        }).show();
    }

    // ======================= كل التطبيقات (وللاختيار) =======================
    private FrameLayout buildAppsPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);
        page.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        appsCol = col;
        applyMargins();

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        appsTitle = Ui.text(this, "كل التطبيقات", 26, Ui.TEXT, true);
        header.addView(appsTitle);
        appsHint = Ui.text(this, "", 15, Ui.MUTED, false);
        appsHint.setPadding(dp(14), 0, 0, 0);
        header.addView(appsHint, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = Ui.text(this, "✕", 32, Ui.TEXT, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.round(Ui.CARD2, dp(14)));
        close.setOnClickListener(v -> hideApps());
        header.addView(close, new LinearLayout.LayoutParams(dp(64), dp(64)));
        col.addView(header);

        ScrollView sv = new ScrollView(this);
        grid = new GridLayout(this);
        sv.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = dp(12);
        col.addView(sv, slp);
        page.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return page;
    }

    private void startPick(int mode, int index) {
        pickMode = mode;
        pickIndex = index;
        if (mode == PICK_ZONE) {
            appsTitle.setText("اختر تطبيق الجانب " + ZONE_NAMES[index]);
            appsHint.setText("اضغط على التطبيق ليعمل في هذا الجانب");
        } else if (mode == PICK_FAV) {
            appsTitle.setText("اختر تطبيقاً للمفضلة (خانة " + (index + 1) + ")");
            appsHint.setText("اضغط على التطبيق لإضافته");
        } else {
            appsTitle.setText("كل التطبيقات");
            appsHint.setText("اضغط لفتحه في نافذة جديدة · مطوّلاً للخيارات");
        }
        appsPage.setVisibility(View.VISIBLE);
    }

    private void hideApps() {
        if (appsPage != null) appsPage.setVisibility(View.GONE);
        pickMode = PICK_NONE;
        pickIndex = -1;
    }

    private void onAppChosen(AppItem a) {
        int mode = pickMode, idx = pickIndex;
        hideApps();
        if (mode == PICK_ZONE) {
            assign(idx, a);
        } else if (mode == PICK_FAV) {
            String[] f = getFavs();
            for (int i = 0; i < f.length; i++) if (f[i].equals(a.pkg)) f[i] = "";   // بلا تكرار
            f[idx] = a.pkg;
            setFavs(f);
        } else {
            openWindow(a);
        }
    }

    /** فتح التطبيق في نافذة جديدة قابلة للإغلاق (أو بملء الشاشة حسب الإعدادات). */
    private void openWindow(AppItem a) {
        String err = WindowLauncher.launchWindow(this, a, selectedDisplay, prefs.getBoolean(KEY_WINDOW, true));
        if (err != null) toast("تعذر التشغيل: " + err);
    }

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        new Thread(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            List<AppItem> out = new ArrayList<>();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                AppItem a = new AppItem();
                a.pkg = pkg;
                a.cls = ri.activityInfo.name;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                out.add(a);
            }
            Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> {
                apps = out;
                appsDirty = false;
                fillGrid();
                renderDock();
                renderZones();
            });
        }).start();
    }

    private void fillGrid() {
        grid.removeAllViews();
        int cell = dp(132);
        int cols = Math.max(4, getResources().getDisplayMetrics().widthPixels / cell - 1);
        grid.setColumnCount(cols);
        for (final AppItem a : apps) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(dp(8), dp(8), dp(8), dp(8));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            c.addView(iv, new LinearLayout.LayoutParams(dp(76), dp(76)));
            TextView t = Ui.text(this, a.label, 16, Ui.TEXT, false);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = cell;
            c.setLayoutParams(glp);
            c.setOnClickListener(v -> onAppChosen(a));
            c.setOnLongClickListener(v -> { appMenu(a); return true; });
            grid.addView(c);
        }
    }

    private AppItem find(String pkg) {
        for (AppItem a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void appMenu(final AppItem a) {
        final String[] fav = getFavs();
        final int favIdx = Arrays.asList(fav).indexOf(a.pkg);
        String[] items = {
                favIdx >= 0 ? "إزالة من المفضلة" : "إضافة إلى المفضلة",
                "تشغيل بملء الشاشة",
                "معلومات التطبيق",
                "حذف التطبيق"
        };
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            if (which == 0) {
                if (favIdx >= 0) {
                    fav[favIdx] = "";
                } else {
                    int empty = Arrays.asList(fav).indexOf("");
                    if (empty < 0) { toast("المفضلة ممتلئة: أزل أو غيّر إحدى الخانات أولاً"); return; }
                    fav[empty] = a.pkg;
                }
                setFavs(fav);
            } else if (which == 1) {
                hideApps();
                String err = WindowLauncher.launch(this, a, selectedDisplay, null);
                if (err != null) toast("تعذر التشغيل: " + err);
            } else if (which == 2) {
                startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
            } else {
                startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
            }
        }).show();
    }

    // ======================= الودجات =======================
    private void showWidgets() {
        message("الودجات الإضافية — قريباً\n\nبيانات السيارة تظهر دائماً في الشريط العلوي. "
                + "هنا سنضيف لاحقاً ودجات أخرى (طقس، موسيقى، اختصارات…).");
    }

    // ======================= الإعدادات =======================
    private void showSettings() {
        final boolean bubble = prefs.getBoolean(KEY_BUBBLE, true), edge = prefs.getBoolean(KEY_EDGE, false);
        final boolean windowed = prefs.getBoolean(KEY_WINDOW, true);
        String[] items = {
                (windowed ? "☑" : "☐") + "  فتح التطبيقات في نافذة جديدة (وإلا بملء الشاشة)",
                (bubble ? "☑" : "☐") + "  الأيقونة العائمة للوصول من أي شاشة",
                (edge ? "☑" : "☐") + "  السحب من حافتي الشاشة لفتح Car Home",
                "الهامش السفلي: " + prefs.getInt(KEY_MB, 0) + " dp   (إن غطّى شريط السيارة الأزرار السفلية)",
                "الهامش العلوي: " + prefs.getInt(KEY_MT, 0) + " dp",
                "فحص قدرات النوافذ والشاشات والتخطيط",
                pendingUpdate != null ? "⬆  تحديث متوفر — اضغط للتحديث" : "⟳  البحث عن تحديث"
        };
        new AlertDialog.Builder(this).setTitle("إعدادات Car Home").setItems(items, (d, which) -> {
            if (which == 0) {
                prefs.edit().putBoolean(KEY_WINDOW, !windowed).apply();
                showSettings();
            } else if (which == 1 || which == 2) {
                prefs.edit().putBoolean(which == 1 ? KEY_BUBBLE : KEY_EDGE, which == 1 ? !bubble : !edge).apply();
                if (!Settings.canDrawOverlays(this)) {
                    message("لا توجد صلاحية الظهور فوق التطبيقات.\nثبّت التطبيق من صفحة التثبيت بالكمبيوتر (تمنحها تلقائياً)، أو نفّذ:\n"
                            + "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                } else {
                    OverlayService.sync(this);
                }
                showSettings();
            } else if (which == 3) {
                pickMargin(KEY_MB, "الهامش السفلي");
            } else if (which == 4) {
                pickMargin(KEY_MT, "الهامش العلوي");
            } else if (which == 5) {
                message(WindowLauncher.capabilityReport(this) + layoutReport());
            } else {
                onUpdateClicked();
            }
        }).show();
    }

    private void pickMargin(final String key, String title) {
        String[] labels = new String[MARGIN_STEPS.length];
        int checked = 0;
        for (int i = 0; i < MARGIN_STEPS.length; i++) {
            labels[i] = MARGIN_STEPS[i] + " dp";
            if (MARGIN_STEPS[i] == prefs.getInt(key, 0)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, checked, (d, which) -> {
            prefs.edit().putInt(key, MARGIN_STEPS[which]).apply();
            applyMargins();
            d.dismiss();
            showSettings();
        }).show();
    }

    /** مقاسات النافذة وهوامش النظام: تكشف إن كان شريط السيارة يغطي جزءاً من التطبيق. */
    private String layoutReport() {
        StringBuilder sb = new StringBuilder("\n\nالتخطيط:\n");
        sb.append(" • نافذة Car Home: ").append(rootFrame.getWidth()).append('×').append(rootFrame.getHeight()).append(" px\n");
        sb.append(" • الكثافة: ").append(getResources().getDisplayMetrics().density).append('\n');
        WindowInsets wi = rootFrame.getRootWindowInsets();
        if (wi != null) {
            sb.append(" • هوامش النظام: أعلى ").append(wi.getSystemWindowInsetTop())
                    .append(" · أسفل ").append(wi.getSystemWindowInsetBottom()).append('\n');
        }
        int[] loc = new int[2];
        dockRow.getLocationOnScreen(loc);
        sb.append(" • موضع شريط المفضلة: y=").append(loc[1]).append(" ارتفاع ").append(dockRow.getHeight()).append('\n');
        return sb.toString();
    }

    // ======================= أدوات =======================
    private void startSafely(Intent i) {
        try { startActivity(i); } catch (Exception e) { toast("تعذر الفتح"); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void message(String m) {
        new AlertDialog.Builder(this).setMessage(m).setPositiveButton("حسناً", null).show();
    }

    // ======================= التحديث الذاتي =======================
    private void checkUpdateQuietly() {
        new Thread(() -> {
            try {
                Updater.Info i = Updater.fetchLatest();
                if (i != null && i.code > Updater.installedCode(this)) ui.post(() -> pendingUpdate = i);
            } catch (Exception ignored) { }
        }).start();
    }

    private void onUpdateClicked() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            message("لكي يحدّث التطبيق نفسه يحتاج صلاحية تثبيت التطبيقات، وشاشة السيارة لا تعرض هذا الخيار في الإعدادات.\n\n"
                    + "ثبّت التطبيق مرة واحدة من صفحة التثبيت بالكمبيوتر (تمنحه الصلاحية تلقائياً):\n"
                    + Updater.installPage() + "\n\nبعدها يعمل التحديث دائماً بدون كمبيوتر.");
            return;
        }
        TextView status = Ui.text(this, "جاري البحث عن تحديث…", 18, Ui.TEXT, false);
        status.setPadding(dp(24), dp(20), dp(24), dp(20));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                Updater.Info i = Updater.fetchLatest();
                long mine = Updater.installedCode(this);
                ui.post(() -> {
                    dlg.dismiss();
                    if (i == null || i.code <= mine) message("لديك آخر نسخة ✅\n\nالنسخة الحالية: v" + Updater.installedName(this));
                    else offerUpdate(i);
                });
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("تعذر الاتصال بـ GitHub.\nتأكد من اتصال الشاشة بالإنترنت.\n\n" + e.getMessage()); });
            }
        }).start();
    }

    private void offerUpdate(Updater.Info i) {
        String notes = i.notes == null || i.notes.trim().isEmpty() ? "" : "\n\nما الجديد:\n" + i.notes.trim();
        new AlertDialog.Builder(this)
                .setTitle("نسخة جديدة متوفرة")
                .setMessage("النسخة الحالية: v" + Updater.installedName(this) + "\nالنسخة الجديدة: " + i.tag + notes)
                .setPositiveButton("تحديث الآن", (d, w) -> install(i))
                .setNegativeButton("لاحقاً", null)
                .show();
    }

    private void install(Updater.Info i) {
        TextView status = Ui.text(this, "جاري التحميل… 0%", 18, Ui.TEXT, false);
        status.setPadding(dp(24), dp(20), dp(24), dp(20));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                final int[] last = {-1};
                Updater.downloadAndInstall(this, i, (done, total) -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != last[0]) {
                        last[0] = pct;
                        ui.post(() -> status.setText(pct >= 0 ? "جاري التحميل… " + pct + "%"
                                : "جاري التحميل… " + (done / 1024) + " KB"));
                    }
                });
                ui.post(dlg::dismiss);
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("فشل التحميل:\n" + e.getMessage()); });
            }
        }).start();
    }
}
