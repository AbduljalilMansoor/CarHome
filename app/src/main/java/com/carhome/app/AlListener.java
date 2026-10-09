package com.carhome.app;

import com.autolink.manager.car.CarPropertyListener;

/**
 * مستمع لحظي لإشارات CAN (بسرعة الشبكة، أسرع بكثير من مرة كل ثانية).
 * يرث من كلاس Autolink الحقيقي الموجود في نظام السيارة.
 * إذا لم تكن المكتبة موجودة، فإن إنشاء هذا الكلاس يفشل ونرجع للقراءة الدورية فقط.
 */
final class AlListener extends CarPropertyListener {
    private final CarBridge bridge;

    AlListener(CarBridge bridge) { this.bridge = bridge; }

    @Override public void onVEHICLESPEEDVSOSIG(float f) { bridge.onSpeed(f, true); }
    @Override public void onENGINESPEED(int i) { bridge.onRpm(i, true); }
    @Override public void onBMS_44_PACKPOWERREALTIME(float f) { bridge.onPower(f, true); }
    @Override public void onFUELROLLINGCOUNTER(float f) { bridge.onFuelCounter(f); }
    @Override public void onHCU_DRIVEMODE_JT(int i) { bridge.onMode(i, true); }
}
