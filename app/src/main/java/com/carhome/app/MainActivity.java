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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Car Home — الشاشة الرئيسية:
 *  1) منطقتان (يمين/يسار) لتشغيل تطبيقين معاً، أو عرض لوحة السيارة المدمجة.
 *  2) زر «تعديل» لتغيير حجم المنطقتين بسحب الفاصل.
 *  3) شريط سفلي: اختيار الشاشة (Display) + التطبيقات المفضلة (حتى 5) + زر «التطبيقات» + الودجات + الإعدادات.
 *  4) يمكن فتحه من أي شاشة بالأيقونة العائمة أو السحب من الحافة (OverlayService).
 */
public class MainActivity extends Activity {
    static final String PREFS = "carhome";
    static final String KEY_PINNED = "pinned", KEY_DISPLAY = "display", KEY_SPLIT = "split";
    static final String KEY_BUBBLE = "bubble", KEY_EDGE = "edge";
    static final String KEY_Z0 = "z0", KEY_Z1 = "z1", KEY_ZD0 = "zd0", KEY_ZD1 = "zd1";
    static final String NEW_TRIP_PKG = "com.newtrip.app";
    static final String DASH = "dash";
    static final int MAX_PINNED = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // بيانات السيارة
    private CarBridge car;
    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean polling;

    // الحالة
    private final String[] zoneContent = {DASH, ""};
    private final int[] zoneDisplay = {0, 0};
    private int activeZone = 1;
    private float split = 0.5f;
    private int selectedDisplay = WindowLauncher.FRONT;
    private boolean editMode;
    private long lastApply;

    // واجهة
    private FrameLayout rootFrame, appsPage;
    private LinearLayout zonesRow, dockRow;
    private final LinearLayout[] zoneCard = new LinearLayout[2];
    private final FrameLayout[] zoneBody = new FrameLayout[2];
    private final TextView[] zoneTitle = new TextView[2];
    private final ImageView[] zoneIcon = new ImageView[2];
    private final Dashboard[] zoneDash = new Dashboard[2];
    private TextView editBtn, displayBtn, vClock;
    private LinearLayout divider;
    private GridLayout grid;

    private List<AppItem> apps = new ArrayList<>();
    private boolean appsDirty = true;
    private Updater.Info pendingUpdate;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            vClock.setText(timeFmt.format(new Date()));
            for (Dashboard d : zoneDash) if (d != null) d.render(car.live);
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
        if (!prefs.contains(KEY_PINNED)) prefs.edit().putString(KEY_PINNED, NEW_TRIP_PKG).apply();
        loadState();
        car = new CarBridge();

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.BG);
        rootFrame.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(12), dp(8), dp(12), dp(10));
        main.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, -2));
        main.addView(buildZones(), new LinearLayout.LayoutParams(-1, 0, 1f));
        main.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, -2));
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

    /** زر Home أو الأيقونة العائمة: أغلق القوائم وأعد إظهار التطبيقات داخل المنطقتين. */
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
        split = clamp(prefs.getFloat(KEY_SPLIT, 0.5f));
        selectedDisplay = prefs.getInt(KEY_DISPLAY, WindowLauncher.FRONT);
        zoneContent[0] = prefs.getString(KEY_Z0, DASH);
        zoneContent[1] = prefs.getString(KEY_Z1, "");
        zoneDisplay[0] = prefs.getInt(KEY_ZD0, 0);
        zoneDisplay[1] = prefs.getInt(KEY_ZD1, 0);
    }

    private void saveZones() {
        prefs.edit().putString(KEY_Z0, zoneContent[0]).putString(KEY_Z1, zoneContent[1])
                .putInt(KEY_ZD0, zoneDisplay[0]).putInt(KEY_ZD1, zoneDisplay[1]).apply();
    }

    private static float clamp(float v) { return Math.max(0.25f, Math.min(0.75f, v)); }

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

    // ======================= الشريط العلوي =======================
    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.text(this, "⌂  Car Home", 18, Ui.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1f));
        vClock = Ui.text(this, "--:--", 22, Ui.TEXT, true);
        bar.addView(vClock);
        return bar;
    }

    // ======================= المنطقتان =======================
    private View buildZones() {
        zonesRow = new LinearLayout(this);
        zonesRow.setOrientation(LinearLayout.HORIZONTAL);
        zonesRow.setBaselineAligned(false);
        zoneCard[0] = buildZone(0);
        zoneCard[1] = buildZone(1);
        divider = buildDivider();
        zonesRow.addView(zoneCard[0], zoneLp(split));
        zonesRow.addView(divider, new LinearLayout.LayoutParams(dp(76), -1));
        zonesRow.addView(zoneCard[1], zoneLp(1f - split));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        zonesRow.setLayoutParams(lp);
        return zonesRow;
    }

    private LinearLayout.LayoutParams zoneLp(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
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
        TextView x = Ui.text(this, "✕", 22, Ui.MUTED, true);
        x.setPadding(dp(14), dp(2), dp(6), dp(2));
        x.setOnClickListener(v -> { zoneContent[z] = ""; saveZones(); renderZone(z); });
        head.addView(x);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        zoneBody[z] = new FrameLayout(this);
        zoneBody[z].setBackground(Ui.round(Ui.BG, dp(12)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(8);
        card.addView(zoneBody[z], blp);
        card.setOnClickListener(v -> setActive(z));
        return card;
    }

    private LinearLayout buildDivider() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setGravity(Gravity.CENTER);
        d.setBackground(Ui.round(Ui.CARD, dp(16)));

        View.OnTouchListener drag = (v, e) -> {
            if (!editMode) return false;
            int[] loc = new int[2];
            zonesRow.getLocationOnScreen(loc);
            if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (e.getAction() == MotionEvent.ACTION_MOVE || e.getAction() == MotionEvent.ACTION_UP) {
                split = clamp((e.getRawX() - loc[0]) / Math.max(1, zonesRow.getWidth()));
                zoneCard[0].setLayoutParams(zoneLp(split));
                zoneCard[1].setLayoutParams(zoneLp(1f - split));
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    prefs.edit().putFloat(KEY_SPLIT, split).apply();
                    ui.postDelayed(() -> { launchZone(0); launchZone(1); }, 250);   // بعد استقرار التخطيط
                }
                return true;
            }
            return true;
        };

        TextView up = Ui.text(this, "◀ ▶", 20, Ui.MUTED, true);
        up.setGravity(Gravity.CENTER);
        up.setOnTouchListener(drag);
        d.addView(up, new LinearLayout.LayoutParams(-1, 0, 1f));

        editBtn = Ui.text(this, "✎\nتعديل", 15, Ui.TEXT, true);
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setBackground(Ui.round(Ui.CARD2, dp(14)));
        editBtn.setPadding(dp(6), dp(14), dp(6), dp(14));
        editBtn.setOnClickListener(v -> toggleEdit());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.setMargins(dp(6), dp(6), dp(6), dp(6));
        d.addView(editBtn, elp);

        TextView down = Ui.text(this, "◀ ▶", 20, Ui.MUTED, true);
        down.setGravity(Gravity.CENTER);
        down.setOnTouchListener(drag);
        d.addView(down, new LinearLayout.LayoutParams(-1, 0, 1f));
        return d;
    }

    private void toggleEdit() {
        editMode = !editMode;
        editBtn.setText(editMode ? "✓\nتم" : "✎\nتعديل");
        editBtn.setBackground(Ui.round(editMode ? Ui.EV : Ui.CARD2, dp(14)));
        divider.setBackground(Ui.round(editMode ? 0xFF1B3A52 : Ui.CARD, dp(16)));
        if (editMode) toast("اسحب السهمين ◀ ▶ لتغيير حجم المنطقتين");
    }

    private void setActive(int z) {
        activeZone = z;
        highlightZones();
    }

    private void highlightZones() {
        for (int i = 0; i < 2; i++) {
            GradientDrawable g = Ui.round(Ui.CARD, dp(16));
            g.setStroke(dp(3), i == activeZone ? Ui.EV : Ui.CARD);
            zoneCard[i].setBackground(g);
        }
    }

    private void renderZones() { renderZone(0); renderZone(1); }

    private void renderZone(final int z) {
        zoneBody[z].removeAllViews();
        zoneDash[z] = null;
        zoneIcon[z].setImageDrawable(null);
        String c = zoneContent[z];
        highlightZones();
        if (c.isEmpty()) {
            zoneTitle[z].setText("المنطقة " + (z + 1));
            LinearLayout col = centerColumn();
            col.addView(Ui.text(this, "＋", 54, Ui.MUTED, true));
            col.addView(Ui.text(this, "اختر تطبيقاً", 18, Ui.MUTED, false));
            col.setOnClickListener(v -> { setActive(z); showApps(); });
            zoneBody[z].addView(col, new FrameLayout.LayoutParams(-1, -1));
        } else if (c.equals(DASH)) {
            zoneTitle[z].setText("لوحة السيارة");
            Dashboard d = new Dashboard(this);
            zoneDash[z] = d;
            d.render(car.live);
            zoneBody[z].addView(d.view, new FrameLayout.LayoutParams(-1, -1));
        } else {
            AppItem a = find(c);
            zoneTitle[z].setText(a == null ? c : a.label);
            LinearLayout col = centerColumn();
            if (a != null) {
                zoneIcon[z].setImageDrawable(a.icon);
                ImageView big = new ImageView(this);
                big.setImageDrawable(a.icon);
                col.addView(big, new LinearLayout.LayoutParams(dp(96), dp(96)));
            }
            boolean other = zoneDisplay[z] != WindowLauncher.FRONT;
            TextView t = Ui.text(this, other
                    ? "يعمل على " + WindowLauncher.displayLabel(zoneDisplay[z], null)
                    : "التطبيق يعمل في هذه المنطقة\nاضغط لإعادة التشغيل", 16, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, 0);
            col.addView(t);
            col.setOnClickListener(v -> { setActive(z); launchZone(z); });
            zoneBody[z].addView(col, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private LinearLayout centerColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        return col;
    }

    /** تعيين تطبيق للمنطقة وتشغيله على الشاشة المختارة. */
    private void assign(int z, AppItem a) {
        zoneContent[z] = a.pkg;
        zoneDisplay[z] = selectedDisplay;
        saveZones();
        renderZone(z);
        launchZone(z);
    }

    private void launchZone(final int z) {
        String c = zoneContent[z];
        if (c.isEmpty() || c.equals(DASH)) return;
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

    /** إعادة إظهار تطبيقات المنطقتين (بعد العودة من تطبيق آخر أو تغيير الحجم). */
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
        displayBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
        displayBtn.setOnClickListener(v -> showDisplayMenu(v));
        bar.addView(displayBtn, new LinearLayout.LayoutParams(-2, -2));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setBackground(Ui.round(Ui.CARD, dp(18)));
        dockRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, -2, 1f);
        dlp.setMargins(dp(12), 0, dp(12), 0);
        bar.addView(dockRow, dlp);

        TextView gear = Ui.text(this, "⚙", 24, Ui.TEXT, true);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.round(Ui.CARD2, dp(14)));
        gear.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(60), dp(60));
        glp.rightMargin = dp(8);
        bar.addView(gear, glp);

        TextView widgets = Ui.text(this, "▦  WIDGETS", 15, Ui.TEXT, true);
        widgets.setGravity(Gravity.CENTER);
        widgets.setBackground(Ui.round(Ui.CARD2, dp(14)));
        widgets.setPadding(dp(16), dp(14), dp(16), dp(14));
        widgets.setOnClickListener(v -> showWidgets());
        bar.addView(widgets, new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    private void updateDisplayButton() {
        displayBtn.setText("▾  Display " + selectedDisplay + (selectedDisplay == WindowLauncher.FRONT ? " (Front)"
                : selectedDisplay == WindowLauncher.REAR ? " (Rear)" : ""));
    }

    private void showDisplayMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, -1, 0, "اختر الشاشة التي يعمل عليها التطبيق").setEnabled(false);
        for (int[] d : WindowLauncher.displays(this)) {
            int id = d[0];
            m.getMenu().add(0, id, id + 1, (id == selectedDisplay ? "✓  " : "     ")
                    + WindowLauncher.displayLabel(id, null));
        }
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() >= 0) {
                selectedDisplay = item.getItemId();
                prefs.edit().putInt(KEY_DISPLAY, selectedDisplay).apply();
                updateDisplayButton();
            }
            return true;
        });
        m.show();
    }

    private void renderDock() {
        dockRow.removeAllViews();
        int n = 0;
        for (String pkg : getPinned()) {
            AppItem a = find(pkg);
            if (a == null || n >= MAX_PINNED) continue;
            n++;
            dockRow.addView(dockCell(a.icon, a.label, v -> assign(activeZone, a), a), dockLp());
        }
        TextView allIcon = Ui.text(this, "⊞", 32, Ui.TEXT, true);
        allIcon.setGravity(Gravity.CENTER);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(10), dp(2), dp(10), dp(2));
        cell.addView(allIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView lbl = Ui.text(this, "التطبيقات", 12, Ui.MUTED, false);
        lbl.setGravity(Gravity.CENTER);
        cell.addView(lbl);
        cell.setOnClickListener(v -> showApps());
        dockRow.addView(cell, dockLp());
    }

    private LinearLayout.LayoutParams dockLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(6), 0, dp(6), 0);
        return lp;
    }

    private View dockCell(android.graphics.drawable.Drawable icon, String label, View.OnClickListener click, AppItem a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(10), dp(2), dp(10), dp(2));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView t = Ui.text(this, label, 12, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setMaxWidth(dp(90));
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(t);
        cell.setOnClickListener(click);
        cell.setOnLongClickListener(v -> { appMenu(a); return true; });
        return cell;
    }

    // ======================= كل التطبيقات =======================
    private FrameLayout buildAppsPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);
        page.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(20));

        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this, "كل التطبيقات", 28, Ui.TEXT, true));
        header.addView(Ui.text(this, "   اضغط لتشغيله في المنطقة المحددة · مطوّلاً للخيارات", 15, Ui.MUTED, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
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

    private void showApps() { appsPage.setVisibility(View.VISIBLE); }

    private void hideApps() { if (appsPage != null) appsPage.setVisibility(View.GONE); }

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
            c.setOnClickListener(v -> { hideApps(); assign(activeZone, a); });
            c.setOnLongClickListener(v -> { appMenu(a); return true; });
            grid.addView(c);
        }
    }

    private AppItem find(String pkg) {
        for (AppItem a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private List<String> getPinned() {
        List<String> l = new ArrayList<>();
        for (String x : prefs.getString(KEY_PINNED, "").split(",")) if (!x.isEmpty()) l.add(x);
        return l;
    }

    private void setPinned(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) { if (sb.length() > 0) sb.append(','); sb.append(x); }
        prefs.edit().putString(KEY_PINNED, sb.toString()).apply();
        renderDock();
    }

    private void appMenu(final AppItem a) {
        if (a == null) return;
        final List<String> pinned = getPinned();
        final boolean isPinned = pinned.contains(a.pkg);
        String[] items = {
                isPinned ? "إلغاء التثبيت من المفضلة" : "تثبيت في المفضلة (حتى 5)",
                "تشغيل بملء الشاشة",
                "معلومات التطبيق",
                "حذف التطبيق"
        };
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            if (which == 0) {
                if (isPinned) pinned.remove(a.pkg);
                else if (pinned.size() >= MAX_PINNED) { toast("المفضلة محدودة بـ " + MAX_PINNED + " تطبيقات"); return; }
                else pinned.add(a.pkg);
                setPinned(pinned);
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
        String[] items = {"لوحة السيارة (سرعة · بطارية · وقود…)", "المزيد من الودجات — قريباً"};
        new AlertDialog.Builder(this).setTitle("الودجات — للمنطقة " + (activeZone + 1))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        zoneContent[activeZone] = DASH;
                        saveZones();
                        renderZone(activeZone);
                    } else {
                        toast("سيتم دعم إضافة ودجات أخرى لاحقاً");
                    }
                }).show();
    }

    // ======================= الإعدادات =======================
    private void showSettings() {
        boolean bubble = prefs.getBoolean(KEY_BUBBLE, true), edge = prefs.getBoolean(KEY_EDGE, false);
        String[] items = {
                (bubble ? "☑" : "☐") + "  الأيقونة العائمة للوصول من أي شاشة",
                (edge ? "☑" : "☐") + "  السحب من حافتي الشاشة لفتح Car Home",
                "فحص قدرات النوافذ والشاشات",
                pendingUpdate != null ? "⬆  تحديث متوفر — اضغط للتحديث" : "⟳  البحث عن تحديث"
        };
        new AlertDialog.Builder(this).setTitle("إعدادات Car Home").setItems(items, (d, which) -> {
            if (which == 0 || which == 1) {
                prefs.edit().putBoolean(which == 0 ? KEY_BUBBLE : KEY_EDGE, which == 0 ? !bubble : !edge).apply();
                if (!Settings.canDrawOverlays(this)) {
                    message("لا توجد صلاحية الظهور فوق التطبيقات.\nثبّت التطبيق من صفحة التثبيت بالكمبيوتر (تمنحها تلقائياً)، أو نفّذ:\n"
                            + "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                } else {
                    OverlayService.sync(this);
                }
                showSettings();
            } else if (which == 2) {
                message(WindowLauncher.capabilityReport(this));
            } else {
                onUpdateClicked();
            }
        }).show();
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
