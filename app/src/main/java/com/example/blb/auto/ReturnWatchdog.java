package com.example.blb.auto;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * 「把人带回来」的闹钟：按下广告里的跳转键之前上闹，回到广告页之后撤掉。
 *
 * <p>为什么需要它。2026-08-23 13:30:35 那一趟第一次真的按开了落地页（淘宝），随后<b>我们自己的
 * 进程就不动了</b>：日志停在「点击 ad_jump」这一行整整五分钟，一句新的都没有，最后 MIUI 在
 * 13:35:45 把它结束掉（{@code exit-info: reason=10 USER REQUESTED, subreason=21 FORCE STOP,
 * description=stop com.example.blb due to SwipeUpClean}）。也就是说：第三方 App 占着前台的时候，
 * 系统可以把我们冻住甚至杀掉，于是「停够秒数再按返回」这段代码根本没机会执行 —— 手按不动的人
 * 就被留在淘宝里出不来了。这是这个 App 最不能出的事故。
 *
 * <p>闹钟是唯一不依赖「我们一直在跑」的机制：系统投递闹钟时会把应用解冻，进程要是已经被杀
 * 也会被重新拉起来。所以哪怕流程本身死在外面，到点也还有人按返回、把菠萝包拉回前台。
 *
 * <p>它只做两件事：按一次全局返回、把菠萝包拉回前台。<b>不读也不点</b>落地页里的任何节点。
 */
public final class ReturnWatchdog extends BroadcastReceiver {

    private static final String TAG = "BlbAuto";
    private static final String ACTION = "com.example.blb.action.RETURN_FROM_LANDING";
    private static final int REQUEST = 4711;
    /** 闹钟比正常返回晚这么久才响，正常情况下永远轮不到它。 */
    private static final long GRACE_MS = 8_000;

    /** 上一次是被闹钟救回来的吗（给日志用，让「被系统冻住了」这件事说得出来）。 */
    private static volatile long lastFiredAt;

    /**
     * 上闹：{@code dwellMs} 之后要是我们还没自己回到菠萝包，就由闹钟按返回。
     *
     * <p>用 {@code setExactAndAllowWhileIdle}：本 App 已经在电池优化白名单里（adb
     * {@code deviceidle whitelist +com.example.blb}），所以不需要额外的精确闹钟授权；
     * 万一系统不给精确闹钟，退回 {@code setAndAllowWhileIdle}（晚一点响，但照样能解冻）。
     */
    public static void arm(Context context, long dwellMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = SystemClock.elapsedRealtime() + Math.max(1_000, dwellMs) + GRACE_MS;
        PendingIntent pi = intent(context);
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
            Log.i(TAG, "已上「带你回来」的闹钟：" + (dwellMs + GRACE_MS) / 1000 + " 秒后"
                    + (exact ? "" : "（系统只给了不精确闹钟，可能晚几分钟）"));
        } catch (Exception e) {
            Log.w(TAG, "上闹钟失败", e);
        }
    }

    /** 撤闹：我们自己回来了，就不用它了。 */
    public static void disarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(intent(context));
        } catch (Exception e) {
            Log.w(TAG, "撤闹钟失败", e);
        }
    }

    /** 闹钟响过之后这个值会变；流程回来时读它，好在日志里如实写「是闹钟把你带回来的」。 */
    public static boolean firedSince(long sinceElapsedRealtime) {
        return lastFiredAt >= sinceElapsedRealtime;
    }

    private static PendingIntent intent(Context context) {
        Intent i = new Intent(context, ReturnWatchdog.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context.getApplicationContext(), REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent received) {
        if (received == null || !ACTION.equals(received.getAction())) return;
        lastFiredAt = SystemClock.elapsedRealtime();
        final Context app = context.getApplicationContext();
        BlbAccessibilityService svc = BlbAccessibilityService.peek();
        Log.i(TAG, "闹钟响了：该把人从落地页带回来了（无障碍" + (svc == null ? "不在" : "在") + "）");
        if (svc != null && svc.isTargetForeground()) return;   // 已经回来了，不用管
        if (svc != null) {
            svc.back();
            // 返回键有时要过一下才生效，隔一会儿再确认一次、必要时直接拉起菠萝包。
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    BlbAccessibilityService s = BlbAccessibilityService.peek();
                    if (s != null && s.isTargetForeground()) return;
                    launchTarget(app);
                }
            }, 1_500);
            return;
        }
        launchTarget(app);
    }

    /** 只用系统给的启动入口把菠萝包拉到前台，不碰它界面里的任何东西。 */
    private static void launchTarget(Context app) {
        try {
            Intent launch = app.getPackageManager()
                    .getLaunchIntentForPackage(BlbAccessibilityService.TARGET_PACKAGE);
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            app.startActivity(launch);
        } catch (Exception e) {
            Log.w(TAG, "闹钟里拉不起菠萝包（后台启动可能被系统拦了）", e);
        }
    }
}
