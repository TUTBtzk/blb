package com.example.blb.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 完成提示走哪条路的判据。
 *
 * <p>为什么值得钉住：用户 2026-09-15 报「跑完没有任何弹窗」。浮动弹窗靠 `startActivity`
 * 从后台弹出，要 MIUI 的「后台弹出界面」放行；权限没了就什么都看不到。App 在前台时改走应用内
 * 对话框，所以「什么算前台、什么算界面还能用」这两条判据不能写错 —— 判错的后果是要么又弹不出来，
 * 要么在一个正在结束的界面上弹对话框直接抛异常。
 */
public class AppForegroundTest {

    @Test
    public void foregroundWithAUsableHostUsesTheInAppDialog() {
        assertEquals(AppForeground.Route.IN_APP, AppForeground.route(true, true));
    }

    @Test
    public void backgroundOrADyingHostFallsBackToTheFloatingWindow() {
        // 定时那趟：进程刚起来，界面从没 start 过。
        assertEquals(AppForeground.Route.FLOATING, AppForeground.route(false, true));
        // 界面正在结束/已销毁：对话框弹不出去，硬弹只会抛异常。
        assertEquals(AppForeground.Route.FLOATING, AppForeground.route(true, false));
        assertEquals(AppForeground.Route.FLOATING, AppForeground.route(false, false));
    }

    @Test
    public void nothingStartedYetMeansNoHostToShowADialogOn() {
        // 服务可以在界面从没起来过时运行（开机后定时那趟），这时必须退到浮动窗口那条路。
        assertFalse(AppForeground.isForeground());
        assertNull(AppForeground.currentActivity());
    }

    // ---------- 2026-09-16：跑完「还是没有任何弹窗」，所以每一步都要能说出自己走到了哪 ----------

    @Test
    public void anInAppDialogThatReallyShowedStaysInApp() {
        assertEquals(AppForeground.Delivery.IN_APP,
                AppForeground.delivery(true, true, true, false));
    }

    /** 应用内那一步没弹出来 → 往下降级到浮动窗口，不能什么都不做。 */
    @Test
    public void aFailedInAppDialogFallsBackToTheFloatingWindow() {
        assertEquals(AppForeground.Delivery.FLOATING,
                AppForeground.delivery(true, true, false, true));
    }

    /** 队列跑的时候前台是菠萝包，我们自己不在前台 → 直接走浮动窗口。 */
    @Test
    public void aRunInTheBackgroundUsesTheFloatingWindow() {
        assertEquals(AppForeground.Delivery.FLOATING,
                AppForeground.delivery(false, false, false, true));
    }

    /** 浮动窗口被系统/MIUI 拒掉（用户 2026-09-16 的现场）→ 必须补一条通知。 */
    @Test
    public void aRefusedFloatingWindowFallsBackToANotification() {
        assertEquals(AppForeground.Delivery.NOTIFICATION,
                AppForeground.delivery(false, false, false, false));
        assertEquals("应用内也失败、浮动也失败时同样要发通知",
                AppForeground.Delivery.NOTIFICATION,
                AppForeground.delivery(true, true, false, false));
    }

    /** 三条路各自要写进运行日志的那句话，别写成空的 —— 下一次排查就靠它。 */
    @Test
    public void everyDeliveryExplainsItselfInTheRunLog() {
        for (AppForeground.Delivery delivery : AppForeground.Delivery.values()) {
            assertFalse(delivery.name(), delivery.note == null || delivery.note.trim().isEmpty());
        }
        assertTrue(AppForeground.Delivery.IN_APP.note.contains("应用内"));
        assertTrue(AppForeground.Delivery.FLOATING.note.contains("浮动"));
        assertTrue(AppForeground.Delivery.NOTIFICATION.note.contains("通知"));
    }
}
