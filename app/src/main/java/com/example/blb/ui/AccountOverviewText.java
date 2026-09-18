package com.example.blb.ui;

import com.example.blb.data.Account;

import java.util.List;

/**
 * 账号页最上面那一行总览：「几个号在跑、手上还有多少券」。
 *
 * <p>为什么要有这一行：账号页原来只有一串卡片，要回答「我一共有几个号在用、还剩多少券」
 * 得自己逐行加。这两个数决定还要不要充值、要不要开新号，必须不点就能看到。
 *
 * <p>余额只累加「读到过」的号（{@code -1}＝一次都没读到），并单独说明有几个号没读到 ——
 * 把没读到的当成 0 加进去，会让人以为券不够了。
 *
 * <p>颜色同样只走三档：绿＝都能自动跑、琥珀＝有号要人管（缺密码／没有启用号之外的问题）、
 * 灰＝一个启用号都没有。这个类不碰 Android API，好让它进普通单元测试。
 */
final class AccountOverviewText {

    private AccountOverviewText() {
    }

    static String line(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) return "还没有账号";
        int enabled = 0;
        int disabled = 0;
        int vouchers = 0;
        int fire = 0;
        int unknownBalance = 0;
        boolean missingPassword = false;
        for (Account a : accounts) {
            if (a == null) continue;
            if (a.enabled) {
                enabled++;
                if (a.needsPassword() && !a.hasPassword()) missingPassword = true;
            } else {
                disabled++;
            }
            if (a.lastKnownVouchers >= 0) vouchers += a.lastKnownVouchers;
            else unknownBalance++;
            if (a.lastKnownCoupons >= 0) fire += a.lastKnownCoupons;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(enabled).append(" 个启用");
        if (disabled > 0) sb.append(" · ").append(disabled).append(" 个停用");
        sb.append(" · 代券合计 ").append(vouchers);
        // 火券是历史遗留（现在只买实付 0 火券的章）：有才提，别让它挤掉代券那一位。
        if (fire > 0) sb.append(" + 火券 ").append(fire);
        if (unknownBalance > 0) {
            sb.append("（").append(unknownBalance).append(" 个号还没读到余额）");
        }
        if (missingPassword) sb.append(" · 有号缺密码，切号会失败");
        return sb.toString();
    }

    static StatusPalette tone(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) return StatusPalette.SKIP;
        boolean anyEnabled = false;
        boolean missingPassword = false;
        for (Account a : accounts) {
            if (a == null || !a.enabled) continue;
            anyEnabled = true;
            if (a.needsPassword() && !a.hasPassword()) missingPassword = true;
        }
        if (!anyEnabled) return StatusPalette.SKIP;
        return missingPassword ? StatusPalette.WARN : StatusPalette.OK;
    }
}
