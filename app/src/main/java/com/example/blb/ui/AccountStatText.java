package com.example.blb.ui;

import com.example.blb.data.AccountStat;

import java.util.List;

/**
 * 「各账号累计」那份数据的两种说法：一整屏一行一个号（{@link #line}），和压缩成一句
 * （{@link #summary}）。
 *
 * <p>原来 8 个号的累计被 {@code TextUtils.join("　", …)} 拼成一条 13sp 小字塞在订阅页里，
 * 横向截断，第三个号往后就看不见了。现在整屏那份一行一个号，一级页面留下面这句总账。
 *
 * <p>这个类不碰任何 Android API，好让它能进普通单元测试。
 */
final class AccountStatText {

    private AccountStatText() {
    }

    /** 一整屏里的第二行：这个号买了多少章、花了多少、手上还剩多少。 */
    static String line(AccountStat s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.chapterCount).append(" 章");
        sb.append("　花 ").append(s.totalVouchers).append(" 代券");
        // 火券只会是历史遗留：新流程只买「实付 0 火券」的章。有就得说，没有不提。
        if (s.totalCost > 0) sb.append('+').append(s.totalCost).append(" 火券");
        sb.append("　余 ").append(s.vouchers >= 0 ? String.valueOf(s.vouchers) : "?").append(" 代券");
        // 免费章（source=OWNED）不算这个号买的章数，但也不能不说 ——
        // 它是「这一章不用再买」的依据。
        if (s.deviceCount > 0) sb.append("　另有 ").append(s.deviceCount).append(" 章免费");
        return sb.toString();
    }

    /**
     * 压成一句：「8 个号共 51 章 · 花掉 1020 代券 · 手上还剩 340 代券」。
     *
     * <p>没有数据就返回空串，让调用方自己说空态。
     */
    static String summary(List<AccountStat> list) {
        if (list == null || list.isEmpty()) return "";
        int chapters = 0;
        int spentVouchers = 0;
        int spentCoupons = 0;
        int left = 0;
        int unknown = 0;
        boolean deviceOnly = false;
        for (AccountStat s : list) {
            if (s == null) continue;
            chapters += Math.max(0, s.chapterCount);
            spentVouchers += Math.max(0, s.totalVouchers);
            spentCoupons += Math.max(0, s.totalCost);
            if (s.deviceCount > 0) deviceOnly = true;
            // vouchers < 0 ＝ 这个号的余额一次都没读到过，不能当 0 混进总数里。
            if (s.vouchers >= 0) left += s.vouchers;
            else unknown++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(list.size()).append(" 个号共 ").append(chapters).append(" 章");
        sb.append(" · 花掉 ").append(spentVouchers).append(" 代券");
        if (spentCoupons > 0) sb.append('+').append(spentCoupons).append(" 火券");
        sb.append(" · 手上还剩 ").append(left).append(" 代券");
        if (unknown > 0) sb.append("（另有 ").append(unknown).append(" 个号没读到余额）");
        // 免费章在 8 个号名下各有一条记录，加起来会是同一批章数的 8 倍，
        // 所以这里只说明它们没算进来，不报数 —— 这本书到底有几章免费，
        // 由「这本书的账本」那一句按章去重后给出。
        if (deviceOnly) sb.append("；不含免费章（不用花券）");
        return sb.toString();
    }
}
