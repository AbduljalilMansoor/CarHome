package com.carhome.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** عند تشغيل السيارة: أعد الأيقونة العائمة وشريطي الحافة إن كانت مفعّلة. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try { OverlayService.sync(ctx); } catch (Exception ignored) { }
        try { BeltService.sync(ctx); } catch (Exception ignored) { }
    }
}
