package com.example.blb.ui;

import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「已登记的章节与订阅情况」压成一句：「登记 612 章 · 已有归属 51 章 · 还差 561 章」。
 *
 * <p>完整列表搬进了 {@link DetailActivity}，但一级页面必须在不点的前提下答出「这本书现在
 * 到哪了」—— 使用者手指不能动，点开才看得到的信息对他等于不存在。
 *
 * <p>「已有归属」按章去重，不是按购买记录条数：同一章万一被两个号买过（那是要修的错），
 * 也只算一章，否则摘要会报出比登记章数还多的「已订」，读起来像是账本坏了。
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
        int deviceOnly = 0;
        if (purchases != null) {
            Set<Long> deviceChapters = new HashSet<>();
            for (Purchase p : purchases) {
                if (p == null || !known.contains(p.chapterId)) continue;
                if (Purchase.SRC_OWNED.equals(p.source)) deviceChapters.add(p.chapterId);
                else owned.add(p.chapterId);
            }
            // 「界面显示已拥有」不算某个号买的，只算这台手机上能看：单独报，不混进归属数。
            for (Long id : deviceChapters) {
                if (!owned.contains(id)) deviceOnly++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("登记 ").append(total).append(" 章");
        sb.append(" · 已有归属 ").append(owned.size()).append(" 章");
        sb.append(" · 还差 ").append(Math.max(0, total - owned.size())).append(" 章");
        if (deviceOnly > 0) sb.append("（另有 ").append(deviceOnly).append(" 章只是本机显示已拥有）");
        return sb.toString();
    }
}
