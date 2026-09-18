package com.example.blb.ui;

import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「已登记的章节与订阅情况」压成一句：「登记 81 章 · 已订阅 4 章 · 免费 47 章 · 还要买 30 章」。
 *
 * <p>完整列表搬进了 {@link DetailActivity}，但一级页面必须在不点的前提下答出「这本书现在
 * 到哪了」—— 使用者手指不能动，点开才看得到的信息对他等于不存在。
 *
 * <p>2026-09-14 真机核对确认多个号可能确实订过同一章；账本保留每个号的事实，
 * 「已订阅」仍按章去重，否则摘要会报出比登记章数还多的「已订」。
 *
 * <p><b>免费章要单独说、而且不能算进「还要买」</b>：{@code source='OWNED'} 只在免费章上写
 * （一章 8 条，8 个号名下各一条），它们一分券都不用花。2026-08-25 拿订阅清单逐章对账证实
 * 这本书第1～47 章全是免费章 —— 把它们算成「还差」会让人以为还要买 77 章、
 * 以为 8 个号拼不出完整一本，而实际上要买的只有 30 章。
 *
 * <p>这个类不碰任何 Android API，好让它能进普通单元测试。
 */
final class ChapterLedgerText {

    private ChapterLedgerText() {
    }

    static String summary(List<Chapter> chapters, List<Purchase> purchases) {
        int[] counts = counts(chapters, purchases);
        if (counts[0] == 0) return "还没登记章节";
        StringBuilder sb = new StringBuilder();
        sb.append("登记 ").append(counts[0]).append(" 章");
        sb.append(" · 已订阅 ").append(counts[1]).append(" 章");
        if (counts[2] > 0) sb.append(" · 免费 ").append(counts[2]).append(" 章");
        sb.append(" · 还要买 ").append(Math.max(0, counts[0] - counts[1] - counts[2])).append(" 章");
        return sb.toString();
    }

    /**
     * 二级页「已登记的章节与订阅情况」标题下面那一句：
     * 「共 489 章 · 已买 132 章 · 免费 64 章 · 还要买 293 章」。
     *
     * <p>和 {@link #summary} 是同一份数字的两种说法：那句是入口卡上的摘要（已上线，不动它），
     * 这一句是整屏页面的开场白 —— 顺序改成「一共有多少 → 其中买了多少 → 还差多少」，
     * 点进来第一眼先看到全书的规模，再看进度。
     *
     * <p>计数只有一份实现（{@link #counts}）：同一个事实两条线各算一遍，迟早会说成两个数。
     */
    static String pageLine(List<Chapter> chapters, List<Purchase> purchases) {
        int[] counts = counts(chapters, purchases);
        // 没有章节时返回空串（和另外两句 pageLine 一个约定）：空态由页面的空态文案说，
        // 两处各写一遍同样的话只会互相打架。
        if (counts[0] == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(counts[0]).append(" 章");
        sb.append(" · 已买 ").append(counts[1]).append(" 章");
        if (counts[2] > 0) sb.append(" · 免费 ").append(counts[2]).append(" 章");
        sb.append(" · 还要买 ").append(Math.max(0, counts[0] - counts[1] - counts[2])).append(" 章");
        return sb.toString();
    }

    /**
     * 数字只在这里算一次：{@code [0]} 登记章数、{@code [1]} 真买过的章数（按章去重）、
     * {@code [2]} 免费章数（按章去重，且真买记录优先，同一章两边都有时算已订阅）。
     */
    private static int[] counts(List<Chapter> chapters, List<Purchase> purchases) {
        int total = chapters == null ? 0 : chapters.size();
        if (total == 0) return new int[]{0, 0, 0};

        Set<Long> known = new HashSet<>();
        for (Chapter c : chapters) {
            if (c != null) known.add(c.id);
        }
        Set<Long> owned = new HashSet<>();
        int free = 0;
        if (purchases != null) {
            Set<Long> freeChapters = new HashSet<>();
            for (Purchase p : purchases) {
                if (p == null || !known.contains(p.chapterId)) continue;
                if (Purchase.SRC_OWNED.equals(p.source)) freeChapters.add(p.chapterId);
                else owned.add(p.chapterId);
            }
            // 免费章按章去重（8 个号名下各一条），而且真买记录优先。
            for (Long id : freeChapters) {
                if (!owned.contains(id)) free++;
            }
        }
        return new int[]{total, owned.size(), free};
    }
}
