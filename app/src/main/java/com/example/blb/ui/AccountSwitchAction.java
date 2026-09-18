package com.example.blb.ui;

import android.content.Context;

import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.data.Account;

/**
 * 「切到某个号」这件事的守卫与发起。账号页和「已登记的章节与订阅情况」页共用同一份。
 *
 * <p>为什么要单独拎出来：切号＝退登＋重登，是这个 App 里最招验证码的动作
 * （见 {@link com.example.blb.auto.SwitchAccountQueue} 的类注释）。以前这套判据只写在
 * {@link DetailActivity} 里，账号页再加一个入口就会变成两份，迟早漂移成「这边拦、那边不拦」——
 * 而判错一次的代价是白挨一次验证码，使用者手指还点不过来。
 */
final class AccountSwitchAction {

    private AccountSwitchAction() {
    }

    /**
     * 现在能不能切。不能切时返回<b>要念给用户的那句话</b>，能切返回 null。
     *
     * <p>顺序和原来的章节页一致：先看无障碍开关（关着就先试着恢复，恢复不了才拒绝），
     * 再看有没有任务在跑。两个都在 {@link #start} 之前问，绝不能反过来。
     */
    static String blocker(Context context) {
        if (AccessibilityAccess.state(context) == AccessibilityAccess.State.DISABLED) {
            AccessibilityAccess.RestoreResult restore = AccessibilityAccess.restoreIfAuthorized(context);
            if (restore == AccessibilityAccess.RestoreResult.NOT_AUTHORIZED
                    || restore == AccessibilityAccess.RestoreResult.FAILED) {
                return "系统无障碍开关已关闭，无法自动恢复，切不了号";
            }
        }
        if (AutomationBus.isBusy()) {
            return "有任务正在运行或账本正在更新，请等结束后再切号";
        }
        return null;
    }

    /** 真的要切时说给用户听的那句话（进度只在签到页的运行日志里）。 */
    static String startedMessage(Account account) {
        return "正在切到「" + account.displayName() + "」：退出当前账号再登它。"
                + "进度看签到页的运行日志";
    }

    /** 交给 {@link AutomationService}：它整趟点着屏幕（无障碍只对亮屏有效）。 */
    static void start(Context context, Account account) {
        AutomationService.startSwitchAccount(context, account.id);
    }
}
