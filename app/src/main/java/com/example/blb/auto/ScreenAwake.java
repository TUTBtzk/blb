package com.example.blb.auto;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/** 手动任务和定时任务共用的亮屏资源，必须在任务结束时关闭。 */
public final class ScreenAwake implements AutoCloseable {
    private static final long LIMIT_MS = 2 * 60 * 60 * 1000L;
    private PowerManager.WakeLock lock;

    private ScreenAwake() {
    }

    /** 只唤醒屏幕，不会绕过用户设置的锁屏密码。 */
    @SuppressWarnings("deprecation")
    public static ScreenAwake acquire(Context context, String label) {
        ScreenAwake awake = new ScreenAwake();
        try {
            PowerManager pm = context.getApplicationContext().getSystemService(PowerManager.class);
            if (pm != null) {
                awake.lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "blb:" + label);
                awake.lock.setReferenceCounted(false);
                awake.lock.acquire(LIMIT_MS);
            }
        } catch (RuntimeException e) {
            Log.w("BlbAuto", "无法唤醒或保持屏幕常亮", e);
            awake.close();
        }
        return awake;
    }

    @Override
    public synchronized void close() {
        PowerManager.WakeLock held = lock;
        lock = null;
        if (held == null) return;
        try {
            if (held.isHeld()) held.release();
        } catch (RuntimeException e) {
            Log.w("BlbAuto", "释放屏幕锁失败", e);
        }
    }
}
