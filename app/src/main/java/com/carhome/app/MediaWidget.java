package com.carhome.app;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** ودجت الوسائط: اسم المقطع والفنان وأزرار السابق/تشغيل-إيقاف/التالي لأي تطبيق موسيقى يعمل. */
final class MediaWidget extends Widget {
    private TextView vTitle, vSub, bPrev, bPlay, bNext;
    private MediaController current;
    private boolean noAccess;

    MediaWidget(android.app.Activity a, CarBridge c) { super(a, c); }

    @Override String id() { return MEDIA; }
    @Override String title() { return "الوسائط"; }
    @Override String description() { return "اسم المقطع وأزرار التحكم بتشغيل الموسيقى من أي تطبيق"; }
    @Override String icon() { return "🎵"; }
    @Override float weight() { return 1.9f; }

    @Override
    View createView() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        vTitle = Ui.text(act, "الوسائط", 14, Ui.TEXT, true);
        vSub = Ui.text(act, "", 11, Ui.MUTED, false);
        for (TextView t : new TextView[]{vTitle, vSub}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
        }
        box.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        bPrev = button("⏮");
        bPlay = button("▶");
        bNext = button("⏭");
        bPrev.setOnClickListener(v -> { if (ready()) current.getTransportControls().skipToPrevious(); });
        bNext.setOnClickListener(v -> { if (ready()) current.getTransportControls().skipToNext(); });
        bPlay.setOnClickListener(v -> {
            if (!ready()) return;
            PlaybackState ps = current.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) current.getTransportControls().pause();
            else current.getTransportControls().play();
        });
        box.addView(bPrev);
        box.addView(bPlay);
        box.addView(bNext);
        col.setOnClickListener(v -> { if (noAccess) explainAccess(); });
        return box;
    }

    private TextView button(String s) {
        TextView t = Ui.text(act, s, 24, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(act, 44));
        t.setPadding(Ui.dp(act, 4), Ui.dp(act, 6), Ui.dp(act, 4), Ui.dp(act, 6));
        return t;
    }

    private boolean ready() {
        if (noAccess) { explainAccess(); return false; }
        if (current == null) { Toast.makeText(act, "لا يوجد تشغيل حالياً — شغّل تطبيق موسيقى", Toast.LENGTH_SHORT).show(); return false; }
        return true;
    }

    @Override
    void refresh() {
        if (vTitle == null) return;
        MediaSessionManager msm = (MediaSessionManager) act.getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(act, MediaListener.class));
            noAccess = false;
        } catch (SecurityException e) {
            noAccess = true;
            current = null;
            vTitle.setText("فعّل الوصول للوسائط");
            vSub.setText("اضغط هنا للتعليمات");
            bPlay.setText("▶");
            return;
        }
        MediaController pick = null;
        for (MediaController c : list) {
            PlaybackState ps = c.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
        }
        if (pick == null && !list.isEmpty()) pick = list.get(0);
        current = pick;
        if (pick == null) {
            vTitle.setText("لا يوجد تشغيل");
            vSub.setText("شغّل تطبيق موسيقى");
            bPlay.setText("▶");
            return;
        }
        MediaMetadata m = pick.getMetadata();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        vTitle.setText(title == null || title.isEmpty() ? pick.getPackageName() : title);
        vSub.setText(artist == null ? "" : artist);
        PlaybackState ps = pick.getPlaybackState();
        bPlay.setText(ps != null && ps.getState() == PlaybackState.STATE_PLAYING ? "⏸" : "▶");
    }

    private void explainAccess() {
        new AlertDialog.Builder(act).setTitle("تفعيل ودجت الوسائط")
                .setMessage("يحتاج الودجت صلاحية «الوصول إلى الإشعارات» ليقرأ جلسات التشغيل.\n\n"
                        + "الأسهل: ثبّت التطبيق من صفحة التثبيت بالكمبيوتر (تمنحها تلقائياً)، أو نفّذ بـ ADB:\n"
                        + "cmd notification allow_listener " + act.getPackageName() + "/" + MediaListener.class.getName())
                .setPositiveButton("فتح الإعدادات", (d, w) -> {
                    try { act.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                    catch (Exception e) { Toast.makeText(act, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show(); }
                })
                .setNegativeButton("إغلاق", null).show();
    }
}
