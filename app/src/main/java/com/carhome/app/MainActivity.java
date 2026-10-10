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
 * Car Home — الشاشة الرئيسية:
 *  1) شريط علوي: بيانات السيارة الحية والساعة.
 *  2) جانب يسار + 3) جانب يمين (50:50 ثابتة): تطبيقان معاً، يُغيَّران بأزرارهما فقط.
 *  4) شريط سفلي: 5 مفضلة + «التطبيقات» + 3 خانات ودجات + WIDGETS + ⚙.
 * أي تطبيق يُفتح من المفضلة أو القائمة يسأل: الشاشة الأمامية أم الخلفية؟ ثم يعمل في نافذة جديدة.
 */
public class MainActivity extends Activity {
    static final String PREFS = "carhome";
    static final String KEY_PINNED = "pinned", KEY_WSLOTS = "wslots";
    static final String KEY_BUBBLE = "bubble", KEY_EDGE = "edge", KEY_WINDOW = "window";
    static final String KEY_Z0 = "z0", KEY_Z1 = "z1";
    static final String KEY_MT = "mt", KEY_MB = "mb";
    static final String NEW_TRIP_PKG = "com.newtrip.app";
    static final int FAV_SLOTS = 5, WIDGET_SLOTS = 3;
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
    private final long[] zoneLaunchedAt = {0, 0};
    private int pickMode = PICK_NONE, pickIndex = -1;

    // واجهة
    private FrameLayout rootFrame, appsPage;
    private LinearLayout main, zonesRow, dockRow, widgetRow, appsCol;
    private TopBar topBar;
    private final LinearLayout[] zoneCard = new LinearLayout[2];
    private final FrameLayout[] zoneBody = new FrameLayout[2];
    private final TextView[] zoneTitle = new TextView[2];
    private final ImageView[] zoneIcon = new ImageView[2];
    private final TextView[] zoneChange = new TextView[2];
    private final TextView[] zoneClose = new TextView[2];
    private final Widget[] slotWidget = new Widget[WIDGET_SLOTS];
    private TextView appsTitle, appsHint;
    private GridLayout grid;

    private List<AppItem> apps = new ArrayList<>();
    private boolean appsDirty = true;
    private Updater.Info pendingUpdate;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            topBar.render(car.live);
            for (Widget w : slotWidget) {
                if (w != null) { try { w.refresh(); } catch (Throwable ignored) { } }
            }
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
        renderWidgets();
        loadApps();
        checkUpdateQuietly();
        try { OverlayService.sync(this); } catch (Exception ignored) { }
        try { BeltService.sync(this); } catch (Exception ignored) { }
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

    /**
     * زر Home أو الأيقونة العائمة: أغلق القوائم فقط.
     * (لا نعيد تشغيل تطبيقات الجانبين تلقائياً هنا: كان ذلك يسبب حلقة إعادة تشغيل متكررة تُجمّد النظام.)
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideApps();
    }

    @Override
    public void onBackPressed() {
        if (appsPage.getVisibility() == View.VISIBLE) hideApps();
    }

    // ======================= الحالة المحفوظة =======================
    private void loadState() {
        zoneContent[0] = cleanZone(prefs.getString(KEY_Z0, ""));
        zoneContent[1] = cleanZone(prefs.getString(KEY_Z1, ""));
    }

    /** نسخ سابقة حفظت "dash" للوحة السيارة (انتقلت إلى الشريط العلوي). */
    private static String cleanZone(String s) { return s == null || s.equals("dash") ? "" : s; }

    private void saveZones() {
        prefs.edit().putString(KEY_Z0, zoneContent[0]).putString(KEY_Z1, zoneContent[1]).apply();
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

    // ======================= الجانبان (50:50 ثابتة) =======================
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
        // ✕ تُفرغ الجانب وتُغلق تطبيقه
        zoneClose[z].setOnClickListener(v -> {
            String pkg = zoneContent[z];
            zoneContent[z] = "";
            saveZones();
            renderZone(z);
            if (!pkg.isEmpty()) WindowLauncher.closeApps(this, Collections.singletonList(pkg));
        });
        head.addView(zoneClose[z]);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        zoneBody[z] = new FrameLayout(this);
        zoneBody[z].setBackground(Ui.round(Ui.BG, dp(12)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(8);
        card.addView(zoneBody[z], blp);
        card.setBackground(Ui.round(Ui.CARD, dp(16)));
        return card;
    }

    private TextView headButton(String label, int color) {
        TextView t = Ui.text(this, label, 17, color, true);
        t.setPadding(dp(14), dp(8), dp(10), dp(8));
        return t;
    }

    private void renderZones() { renderZone(0); renderZone(1); }

    private void renderZone(final int z) {
        zoneBody[z].removeAllViews();
        zoneIcon[z].setImageDrawable(null);
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
            TextView t = Ui.text(this, "اضغط هنا لتشغيل التطبيق في هذا الجانب\n(أو لإعادة تشغيله)", 16, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, 0);
            col.addView(t);
            col.setOnClickListener(v -> launchZone(z));
        }
        zoneBody[z].addView(col, new FrameLayout.LayoutParams(-1, -1));
    }

    /** تعيين تطبيق لجانب (بدون تشغيله؛ يُشغَّل بالضغط على الجانب). ولا يجتمع مع المفضلة. */
    private void assign(int z, AppItem a) {
        zoneContent[z] = a.pkg;
        String[] f = getFavs();
        boolean moved = false;
        for (int i = 0; i < f.length; i++) if (f[i].equals(a.pkg)) { f[i] = ""; moved = true; }
        if (moved) setFavs(f);
        saveZones();
        renderZone(z);
        toast(moved ? "نُقل التطبيق من المفضلة إلى الجانب " + ZONE_NAMES[z] + " — اضغط الجانب لتشغيله"
                : "تم التعيين — اضغط الجانب لتشغيل التطبيق");
    }

    /** رقم الجانب الذي فيه التطبيق، أو -1. */
    private int zoneOf(String pkg) {
        for (int z = 0; z < 2; z++) if (zoneContent[z].equals(pkg)) return z;
        return -1;
    }

    /** لا يُسمح بوضع تطبيق في المفضلة إن كان في أحد الجانبين. */
    private boolean blockFavorite(AppItem a) {
        int z = zoneOf(a.pkg);
        if (z < 0) return false;
        message("«" + a.label + "» مضاف في الجانب " + ZONE_NAMES[z] + " ولا يمكن وضعه في المفضلة أيضاً.\n\n"
                + "أزله من الجانب أولاً (زر ✕) أو اختر تطبيقاً آخر.");
        return true;
    }

    /** تشغيل تطبيق الجانب بطلب صريح من المستخدم فقط (بدون أي إعادة تشغيل تلقائية). */
    private void launchZone(final int z) {
        String c = zoneContent[z];
        if (c.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - zoneLaunchedAt[z] < 1500) return;   // منع الضغط المتكرر
        zoneLaunchedAt[z] = now;
        final AppItem a = find(c);
        if (a == null) { toast("التطبيق غير مثبّت"); return; }
        zoneBody[z].post(() -> {
            int[] loc = new int[2];
            zoneBody[z].getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + zoneBody[z].getWidth(), loc[1] + zoneBody[z].getHeight());
            String err = WindowLauncher.launch(this, a, WindowLauncher.FRONT, r);
            if (err != null) toast("تعذر التشغيل: " + err);
        });
    }

    // ======================= الشريط السفلي =======================
    private View buildBottomBar() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBaselineAligned(false);
        bar.setPadding(0, dp(8), 0, 0);

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setBackground(Ui.round(Ui.CARD, dp(18)));
        dockRow.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(112), 5.4f);
        dlp.rightMargin = dp(8);
        bar.addView(dockRow, dlp);

        widgetRow = new LinearLayout(this);
        widgetRow.setOrientation(LinearLayout.HORIZONTAL);
        widgetRow.setGravity(Gravity.CENTER);
        widgetRow.setBackground(Ui.round(Ui.CARD, dp(18)));
        widgetRow.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, dp(112), 5.0f);
        wlp.rightMargin = dp(8);
        bar.addView(widgetRow, wlp);

        TextView gear = Ui.text(this, "⚙", 26, Ui.TEXT, true);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.round(Ui.CARD2, dp(14)));
        gear.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(56), dp(56));
        glp.rightMargin = dp(8);
        bar.addView(gear, glp);

        TextView widgets = Ui.text(this, "▦\nWIDGETS", 12, Ui.TEXT, true);
        widgets.setGravity(Gravity.CENTER);
        widgets.setBackground(Ui.round(Ui.CARD2, dp(14)));
        widgets.setPadding(dp(10), dp(8), dp(10), dp(8));
        widgets.setOnClickListener(v -> showWidgetLibrary(-1));
        bar.addView(widgets, new LinearLayout.LayoutParams(-2, dp(56)));
        return bar;
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
        LinearLayout all = dockCell(null, "التطبيقات");
        ((TextView) all.getChildAt(0)).setText("⊞");
        all.setOnClickListener(v -> startPick(PICK_NONE, -1));
        dockRow.addView(all, dockLp());
    }

    private LinearLayout.LayoutParams dockLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    /** خانة: أيقونة (أو ＋ إن لم يوجد) وتحتها اسم. الابن الأول دائماً الأيقونة. */
    private LinearLayout dockCell(Drawable icon, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(2), dp(2), dp(2), dp(2));
        cell.setClickable(true);
        View iconView;
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iconView = iv;
        } else {
            TextView plus = Ui.text(this, "＋", 26, Ui.MUTED, true);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.round(Ui.CARD2, dp(14)));
            iconView = plus;
        }
        cell.addView(iconView, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView t = Ui.text(this, label, 12, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return cell;
    }

    private void favMenu(final int slot, final AppItem a) {
        String[] items = {"تغيير التطبيق", "إزالة من المفضلة", "إغلاق التطبيق (إن كان مفتوحاً)"};
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            if (which == 0) {
                startPick(PICK_FAV, slot);
            } else if (which == 1) {
                String[] f = getFavs();
                f[slot] = "";
                setFavs(f);
            } else {
                WindowLauncher.closeApps(this, Collections.singletonList(a.pkg));
            }
        }).show();
    }

    // ---------- خانات الودجات ----------
    private String[] getWidgetSlots() {
        String[] f = new String[WIDGET_SLOTS];
        Arrays.fill(f, "");
        String[] p = prefs.getString(KEY_WSLOTS, "").split(",", -1);
        for (int i = 0; i < WIDGET_SLOTS && i < p.length; i++) f[i] = p[i];
        return f;
    }

    private void setWidgetSlots(String[] f) {
        prefs.edit().putString(KEY_WSLOTS, String.join(",", f)).apply();
        renderWidgets();
    }

    private void renderWidgets() {
        widgetRow.removeAllViews();
        String[] slots = getWidgetSlots();
        for (int i = 0; i < WIDGET_SLOTS; i++) {
            final int slot = i;
            Widget w = slots[i].isEmpty() ? null : Widget.create(slots[i], this, car);
            slotWidget[i] = w;
            LinearLayout.LayoutParams lp;
            if (w == null) {
                LinearLayout empty = new LinearLayout(this);
                empty.setOrientation(LinearLayout.VERTICAL);
                empty.setGravity(Gravity.CENTER);
                empty.setBackground(Ui.round(Ui.CARD2, dp(14)));
                empty.addView(Ui.text(this, "＋", 22, Ui.MUTED, true));
                empty.addView(Ui.text(this, "ودجت", 11, Ui.MUTED, false));
                empty.setOnClickListener(v -> showWidgetLibrary(slot));
                lp = new LinearLayout.LayoutParams(0, -1, 0.8f);
                widgetRow.addView(empty, lp);
            } else {
                View v = w.createView();
                v.setBackground(Ui.round(Ui.CARD2, dp(14)));
                v.setOnLongClickListener(x -> { widgetMenu(slot); return true; });
                lp = new LinearLayout.LayoutParams(0, -1, w.weight());
                widgetRow.addView(v, lp);
            }
            lp.setMargins(dp(3), 0, dp(3), 0);
        }
    }

    private void widgetMenu(final int slot) {
        String[] items = {"تغيير الودجت", "إزالة الودجت"};
        new AlertDialog.Builder(this).setTitle("الودجت").setItems(items, (d, which) -> {
            if (which == 0) {
                showWidgetLibrary(slot);
            } else {
                String[] s = getWidgetSlots();
                s[slot] = "";
                setWidgetSlots(s);
            }
        }).show();
    }

    /** مكتبة الودجات. targetSlot = -1: اسأل عن الخانة بعد الاختيار. */
    private void showWidgetLibrary(final int targetSlot) {
        final String[] ids = Widget.ALL;
        String[] items = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Widget w = Widget.create(ids[i], this, car);
            items[i] = w.icon() + "  " + w.title() + "\n" + w.description();
        }
        new AlertDialog.Builder(this).setTitle("مكتبة الودجات")
                .setItems(items, (d, which) -> {
                    if (targetSlot >= 0) putWidget(targetSlot, ids[which]);
                    else chooseWidgetSlot(ids[which]);
                })
                .setNegativeButton("إغلاق", null).show();
    }

    private void chooseWidgetSlot(final String id) {
        String[] cur = getWidgetSlots();
        String[] items = new String[WIDGET_SLOTS];
        for (int i = 0; i < WIDGET_SLOTS; i++) {
            Widget w = cur[i].isEmpty() ? null : Widget.create(cur[i], this, car);
            items[i] = "الخانة " + (i + 1) + (w == null ? " — فارغة" : " — " + w.title());
        }
        new AlertDialog.Builder(this).setTitle("ضع الودجت في أي خانة؟")
                .setItems(items, (d, which) -> putWidget(which, id))
                .setNegativeButton("إلغاء", null).show();
    }

    private void putWidget(int slot, String id) {
        String[] s = getWidgetSlots();
        for (int i = 0; i < s.length; i++) if (s[i].equals(id)) s[i] = "";   // الودجت مرة واحدة
        s[slot] = id;
        setWidgetSlots(s);
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
        if (mode == PICK_FAV && blockFavorite(a)) return;   // تبقى القائمة مفتوحة لاختيار تطبيق آخر
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

    // ======================= تشغيل التطبيقات =======================
    /** يسأل: الشاشة الأمامية أم الخلفية؟ ثم يفتح التطبيق في نافذة جديدة (أو بملء الشاشة حسب الإعدادات). */
    private void openWindow(AppItem a) { askDisplay(a, prefs.getBoolean(KEY_WINDOW, true)); }

    private void askDisplay(final AppItem a, final boolean windowed) {
        new AlertDialog.Builder(this)
                .setTitle(a.label + " — على أي شاشة؟")
                .setPositiveButton("الأمامية (Front)", (d, w) -> startApp(a, WindowLauncher.FRONT, windowed))
                .setNegativeButton("الخلفية (Rear)", (d, w) -> startApp(a, WindowLauncher.REAR, windowed))
                .setNeutralButton("إلغاء", null)
                .show();
    }

    private void startApp(AppItem a, int display, boolean windowed) {
        String err = WindowLauncher.launchWindow(this, a, display, windowed);
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
                "إغلاق التطبيق (إن كان مفتوحاً)",
                "معلومات التطبيق",
                "حذف التطبيق"
        };
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            if (which == 0) {
                if (favIdx >= 0) {
                    fav[favIdx] = "";
                } else {
                    if (blockFavorite(a)) return;
                    int empty = Arrays.asList(fav).indexOf("");
                    if (empty < 0) { toast("المفضلة ممتلئة: أزل أو غيّر إحدى الخانات أولاً"); return; }
                    fav[empty] = a.pkg;
                }
                setFavs(fav);
            } else if (which == 1) {
                hideApps();
                askDisplay(a, false);
            } else if (which == 2) {
                hideApps();
                WindowLauncher.closeApps(this, Collections.singletonList(a.pkg));
            } else if (which == 3) {
                startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
            } else {
                startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
            }
        }).show();
    }

    // ======================= الإعدادات =======================
    private interface Act { void run(); }

    private void showSettings() {
        final boolean bubble = prefs.getBoolean(KEY_BUBBLE, true), edge = prefs.getBoolean(KEY_EDGE, false);
        final boolean windowed = prefs.getBoolean(KEY_WINDOW, true);
        final List<String> labels = new ArrayList<>();
        final List<Act> actions = new ArrayList<>();

        labels.add((windowed ? "☑" : "☐") + "  فتح التطبيقات في نافذة جديدة (وإلا بملء الشاشة)");
        actions.add(() -> { prefs.edit().putBoolean(KEY_WINDOW, !windowed).apply(); showSettings(); });

        labels.add((bubble ? "☑" : "☐") + "  الأيقونة العائمة (مطوّلاً عليها = إغلاق كل النوافذ)");
        actions.add(() -> { toggleOverlay(KEY_BUBBLE, !bubble); showSettings(); });

        labels.add((edge ? "☑" : "☐") + "  السحب من حافتي الشاشة لفتح Car Home");
        actions.add(() -> { toggleOverlay(KEY_EDGE, !edge); showSettings(); });

        labels.add("إغلاق كل النوافذ والتطبيقات المفتوحة من Car Home");
        actions.add(() -> {
            zoneContent[0] = "";
            zoneContent[1] = "";
            saveZones();
            renderZones();
            WindowLauncher.closeAll(this);
        });

        labels.add("تفعيل النوافذ الحرة وإجبار التطبيقات على تغيير الحجم (ثم أعد تشغيل السيارة)");
        actions.add(() -> {
            String err = WindowLauncher.enableFreeform(this);
            if (err == null) {
                message("تم الضبط ✅\nأعد تشغيل السيارة ليسري المفعول، ثم جرّب تشغيل تطبيق في الجانبين. "
                        + "هذا يمنع التطبيقات غير القابلة لتغيير الحجم من فتح بملء الشاشة.");
            } else {
                message("لا توجد صلاحية تعديل إعدادات النظام.\nثبّت التطبيق من صفحة التثبيت بالكمبيوتر (تمنحها تلقائياً)، أو نفّذ بـ ADB:\n"
                        + "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\n"
                        + "أو مباشرة:\nsettings put global enable_freeform_support 1\nsettings put global force_resizable_activities 1");
            }
        });

        labels.add("الهامش السفلي: " + prefs.getInt(KEY_MB, 0) + " dp   (إن غطّى شريط السيارة الأزرار السفلية)");
        actions.add(() -> pickMargin(KEY_MB, "الهامش السفلي"));

        labels.add("الهامش العلوي: " + prefs.getInt(KEY_MT, 0) + " dp");
        actions.add(() -> pickMargin(KEY_MT, "الهامش العلوي"));

        labels.add("فحص قدرات النوافذ والشاشات والتخطيط");
        actions.add(() -> message(WindowLauncher.capabilityReport(this) + layoutReport()));

        labels.add(pendingUpdate != null ? "⬆  تحديث متوفر — اضغط للتحديث" : "⟳  البحث عن تحديث");
        actions.add(this::onUpdateClicked);

        new AlertDialog.Builder(this).setTitle("إعدادات Car Home")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
    }

    private void toggleOverlay(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
        if (!Settings.canDrawOverlays(this)) {
            message("لا توجد صلاحية الظهور فوق التطبيقات.\nثبّت التطبيق من صفحة التثبيت بالكمبيوتر (تمنحها تلقائياً)، أو نفّذ:\n"
                    + "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        } else {
            OverlayService.sync(this);
        }
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
