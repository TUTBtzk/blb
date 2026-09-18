package com.example.blb.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * App 现在在不在前台，以及「这一次完成提示该走哪条路」的判据。
 *
 * <p>为什么需要它：完成提示从 Service 里 `startActivity` 弹浮动窗口，而 Android 10 起
 * 「从后台启动界面」要 MIUI 的<b>后台弹出界面</b>权限放行；权限被拒、或者重装之后失效，
 * 队列跑完屏幕上就什么都不会出现 —— 用户 2026-09-15 报的「没有任何弹窗」就是这个形状
 * （连他自己在前台点的小动作也一起看不到，那时候本来根本不必冒这个险）。
 * App 就在前台时改弹应用内对话框：一定弹得出来，也不需要任何后台权限。
 *
 * <p>前台状态靠 {@code ActivityLifecycleCallbacks} 数「已 start 未 stop」的界面数得到，
 * 不依赖任何权限。判据 {@link #route} 是纯函数：这个项目没有 Robolectric，
 * 只有纯函数才测得动（见 {@code AppForegroundTest}）。
 */
public final class AppForeground {

    /** 这次提示走哪条路。 */
    public enum Route { IN_APP, FLOATING }

    /** 提示最终落在哪里 —— 写运行日志、决定要不要补一条通知都用它。 */
    public enum Delivery {
        IN_APP("应用内对话框"),
        FLOATING("浮动弹窗（应用不在前台）"),
        NOTIFICATION("浮动弹窗被系统拒绝，已发通知");

        /** 写进运行日志的那句话：下一次「为什么没弹窗」看日志就能答。 */
        public final String note;

        Delivery(String note) {
            this.note = note;
        }
    }

    private static int started;
    private static WeakReference<Activity> current = new WeakReference<>(null);
    private static boolean attached;

    private AppForeground() {
    }

    /**
     * 挂上生命周期回调。App 没有自定义 Application，所以由 {@code MainActivity.onCreate} 挂一次。
     *
     * <p>服务在界面从没起来过的情况下（开机后定时那趟）也会跑：那时 {@link #isForeground()} 是 false，
     * 提示自然退回浮动窗口那条路 —— 这正是要的行为，不需要额外判断。
     */
    public static void attach(Application app) {
        if (attached) return;
        attached = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
            }

            @Override public void onActivityStarted(Activity activity) {
                started++;
                current = new WeakReference<>(activity);
            }

            @Override public void onActivityResumed(Activity activity) {
                current = new WeakReference<>(activity);
            }

            @Override public void onActivityPaused(Activity activity) {
            }

            @Override public void onActivityStopped(Activity activity) {
                started = Math.max(0, started - 1);
                Activity now = current.get();
                if (now == activity) current = new WeakReference<>(null);
            }

            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
            }

            @Override public void onActivityDestroyed(Activity activity) {
                Activity now = current.get();
                if (now == activity) current = new WeakReference<>(null);
            }
        });
    }

    /** 现在前台的那个界面；没有就返回 null。 */
    @Nullable
    public static Activity currentActivity() {
        return current.get();
    }

    /** 有没有界面在前台。 */
    public static boolean isForeground() {
        return started > 0;
    }

    /**
     * 这一次提示走哪条路。
     *
     * @param foreground  App 在前台（有界面已 start 未 stop）
     * @param hostUsable  前台那个界面还能用（没在结束、没被销毁）—— 正在结束的界面弹不出对话框，
     *                     硬弹只会抛异常，那种时候老老实实退回浮动窗口
     */
    static Route route(boolean foreground, boolean hostUsable) {
        return foreground && hostUsable ? Route.IN_APP : Route.FLOATING;
    }

    /** 真机调用这一条；判据本身仍是上面那个纯函数。 */
    static Route routeNow() {
        return route(isForeground(), hostUsable(currentActivity()));
    }

    /**
     * 提示最终落在哪里。三条路的顺序就是代码里的顺序：应用内 → 浮动窗口 → 通知。
     *
     * <p>抽成纯函数是为了能单测：这条判据错了的表现是「跑完什么都不弹」，而它只在真机上
     * 跑完一整趟才看得到一次（这个项目没有 Robolectric，界面代码测不了）。
     *
     * @param foreground      App 在前台
     * @param hostUsable      前台那个界面还能用来弹对话框
     * @param inAppShown      应用内对话框真的弹出来了（弹不出来要往下降级）
     * @param floatingStarted 浮动窗口真的启动成功（startActivity 没抛异常）
     */
    static Delivery delivery(boolean foreground, boolean hostUsable,
                             boolean inAppShown, boolean floatingStarted) {
        if (route(foreground, hostUsable) == Route.IN_APP && inAppShown) return Delivery.IN_APP;
        return floatingStarted ? Delivery.FLOATING : Delivery.NOTIFICATION;
    }

    /**
     * 前台那个界面能不能拿来弹对话框。
     *
     * <p>三种情况不行：没有界面、界面正在结束/已销毁（对话框弹不出去，硬弹只会抛异常）、
     * 以及前台那个界面本身就是我们自己的浮动完成弹窗 —— 那种时候它自己会被新结论替换
     * （{@code singleTop} + {@code onNewIntent}），在它上面再叠一个对话框只会变成两层提示。
     */
    private static boolean hostUsable(@Nullable Activity host) {
        return host != null && !host.isFinishing() && !host.isDestroyed()
                && !(host instanceof DoneDialogActivity);
    }
}
