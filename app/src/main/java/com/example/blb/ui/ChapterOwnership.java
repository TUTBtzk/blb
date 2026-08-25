package com.example.blb.ui;

import com.example.blb.data.Purchase;

import java.util.ArrayList;
import java.util.List;

/**
 * 一章的归属：<b>哪些号真花过钱／手工补录过</b>（{@link #buyerIds}），以及有几条记录只是
 * 「这台手机上显示已拥有」（{@link #deviceOnly}）。
 *
 * <p>为什么要单独抽出来：章节行点一下就会退登当前账号、去登这一章的买家 —— 那是最招验证码
 * 的动作，所以「谁是买家」这个判断必须只有一份，而且能被单元测试断死。
 * {@code source='OWNED'} 那种记录答不出买家（「已下载」是本机状态、8 个号共用），
 * 拿它当买家去切号，等于随便挑一个号退登重登，白挨一次验证码。
 *
 * <p>这个类不碰任何 Android API，好让它能进普通单元测试。
 */
final class ChapterOwnership {

    /** 真买／补录过这一章的账号 id，按记录出现的顺序，同一个号只出现一次。 */
    final List<Long> buyerIds = new ArrayList<>();
    /** 只是「本机显示已拥有」的记录条数 —— 它们答不出买家。 */
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
