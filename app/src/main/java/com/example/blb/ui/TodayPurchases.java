package com.example.blb.ui;

import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「今天新订阅了几章」：签到页那一行摘要的最后一段。
 *
 * <p>为什么按章去重、而且排除免费章：一章可能被两个号各买过一次（2026-09-14 已确认这是真实历史），
 * 免费章（{@code source=OWNED}）更是 8 个号名下各有一条、一分券都没花。照行数报「新订阅」会多报，
 * 而这个数字是给「今天这趟有收获吗」看的。
 *
 * <p>数据来自已经存在的「最近订阅记录」观察（{@code observeRecentRows}，按买入时间倒序），
 * 不新增任何 DAO 查询；窗口被截断（最旧一行仍是今天的）时由 {@link #truncated} 说出来，
 * 界面改用「≥ N 章」，不编一个看着精确的假数。
 */
final class TodayPurchases {

    private TodayPurchases() {
    }

    /** 今天真实新订阅的章数；{@code since} 是今天 0 点。 */
    static int chapters(List<PurchaseRow> rows, long since) {
        if (rows == null || rows.isEmpty()) return 0;
        Set<Long> chapters = new HashSet<>();
        for (PurchaseRow r : rows) {
            if (r == null || r.purchasedAt < since) continue;
            if (Purchase.SRC_OWNED.equals(r.source)) continue;
            chapters.add(r.chapterId);
        }
        return chapters.size();
    }

    /**
     * 窗口里最旧的一行仍然是今天的 —— 说明今天买的记录可能比窗口还多，数字只能当「≥ N」用。
     * 传入的列表必须是按买入时间倒序的（{@code observeRecentRows} 的排序）。
     */
    static boolean truncated(List<PurchaseRow> rows, long since) {
        if (rows == null || rows.isEmpty()) return false;
        PurchaseRow oldest = rows.get(rows.size() - 1);
        return oldest != null && oldest.purchasedAt >= since;
    }
}
