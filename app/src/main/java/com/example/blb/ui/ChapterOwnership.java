package com.example.blb.ui;

import com.example.blb.data.Purchase;

import java.util.ArrayList;
import java.util.List;

/**
 * 一章的归属：<b>哪些号真花过钱／手工补录过</b>（{@link #buyerIds}），以及有几条记录
 * 只说明「这一章免费、不用买」（{@link #deviceOnly}）。
 *
 * <p>为什么要单独抽出来：章节行点一下就会退登当前账号、去登这一章的买家 —— 那是最招验证码
 * 的动作，所以「谁是买家」这个判断必须只有一份，而且能被单元测试断死。
 * {@code source='OWNED'} 那种记录<b>没有买家</b>：现在它只在免费章上写（没有锁的行，
 * 一章 8 条、8 个号名下各一条），2026-08-25 拿订阅清单逐章对账证实过。
 * 拿它当买家去切号，等于随便挑一个号退登重登，白挨一次验证码。
 *
 * <p>这个类不碰任何 Android API，好让它能进普通单元测试。
 */
final class ChapterOwnership {

    /** 真买／补录过这一章的账号 id，按记录出现的顺序，同一个号只出现一次。 */
    final List<Long> buyerIds = new ArrayList<>();
    /** {@code source='OWNED'} 的记录条数 —— 免费章，没有买家。 */
    int deviceOnly;

    private ChapterOwnership() {
    }

    static ChapterOwnership of(List<Purchase> purchases, long chapterId) {
        ChapterOwnership own = new ChapterOwnership();
        if (purchases == null) return own;
        for (Purchase p : purchases) {
            if (p == null || p.chapterId != chapterId) continue;
            if (Purchase.SRC_OWNED.equals(p.source)) {
                own.deviceOnly++;
            } else if (!own.buyerIds.contains(p.accountId)) {
                own.buyerIds.add(p.accountId);
            }
        }
        return own;
    }

    boolean hasBuyer() {
        return !buyerIds.isEmpty();
    }
}
