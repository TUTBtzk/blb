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
        int total = chapters == null ? 0 : chapters.size();
        if (total == 0) return "还没登记章节";

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
            // 免费章按章去重（8 个号名下各一条），而且真买记录优先：万一同一章两边都有，
            // 算已订阅，不算免费章。
            for (Long id : freeChapters) {
                if (!owned.contains(id)) free++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("登记 ").append(total).append(" 章");
        sb.append(" · 已订阅 ").append(owned.size()).append(" 章");
        if (free > 0) sb.append(" · 免费 ").append(free).append(" 章");
        sb.append(" · 还要买 ").append(Math.max(0, total - owned.size() - free)).append(" 章");
        return sb.toString();
    }
}
