package com.example.blb.ui;

import com.example.blb.data.Account;
import com.example.blb.util.Texts;

/**
 * 账号页每一行那句「这个号现在什么状况」。
 *
 * <p>原来那一行是把登录名、登录方式、有没有存密码、余额、上次签到时间拼在一起，
 * 信息都在但读不出重点。现在只留一句：<b>能不能自动跑 · 还有多少代券 · 上次什么时候签的</b>，
 * 顺序按「会不会挡住自动流程」排 —— 缺密码、已停用这两件事必须先看见，剩下的才是余额和时间。
 *
 * <p>昵称只在和显示名不一样时才写出来：它是「切号之后到底登对没有」的核对依据。
 *
 * <p>时间由调用方格式化好传进来（{@code DateFormat} 是 Android API，不能进这个类），
 * 于是这一句可以在 JVM 单测里断死。
 */
final class AccountRowText {

    private AccountRowText() {
    }

    /** @param lastCheckInText 上次签到时间，已格式化；空串＝还没签过 */
    static String statusLine(Account a, String lastCheckInText) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!a.enabled) {
            sb.append("已停用，不参加自动签到");
        } else if (a.needsPassword() && !a.hasPassword()) {
            sb.append("缺密码，切号会失败");
        } else {
            sb.append("可以自动登录");
        }
        sb.append(" · 代券 ").append(a.lastKnownVouchers >= 0
                ? String.valueOf(a.lastKnownVouchers) : "?");
        // 火券只可能是历史遗留：为 0 或没读到就整段不提，别占着这一行的位置。
        if (a.lastKnownCoupons > 0) sb.append("（火券 ").append(a.lastKnownCoupons).append("）");
        sb.append(" · ").append(Texts.isBlank(lastCheckInText)
                ? "还没签过" : "上次签到 " + lastCheckInText);
        if (!Texts.isBlank(a.nickname) && !a.nickname.equals(a.displayName())) {
            sb.append(" · 菠萝包昵称 ").append(a.nickname);
        }
        return sb.toString();
    }
}
