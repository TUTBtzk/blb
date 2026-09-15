package com.example.blb.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 2026-09-14 真机核对把用户以前用多个号真实买过的章节拒成存疑，旧「一章最多一个付费号」作废。
 * 账本按账号保留服务器事实；只有完整远端明细能补跨号历史，自动购买和人工补录仍不能制造重复。
 */
public final class LedgerWritePolicy {
    public enum Action { INSERT, KEEP, REJECT }

    private static final String OTHER_PAID_MESSAGE = "这一章已有其他账号花过券；如果服务器上确实两个号都订过，请用『核对订阅清单』按明细补录";

    public static final class Decision {
        public final Action action;
        public final long existingId;
        public final String message;

        private Decision(Action action, long existingId, String message) {
            this.action = action;
            this.existingId = existingId;
            this.message = message;
        }
    }

    private LedgerWritePolicy() { }

    public static Decision decide(Purchase incoming, List<Purchase> chapterRows) {
        return decide(incoming, chapterRows, false);
    }

    /**
     * 2026-09-14 修复真实跨号历史后，CSV 自填的 REMOTE_DETAIL 不能冒充完整读屏证据。
     * 原来源仍保留作备份事实；完全相同的旧行可幂等重放，只有新增跨号付费事实被挡住。
     */
    public static Decision decideImported(Purchase incoming, List<Purchase> chapterRows) {
        return decide(incoming, chapterRows, true);
    }

    private static Decision decide(Purchase incoming, List<Purchase> chapterRows, boolean imported) {
        if (incoming == null || incoming.accountId <= 0 || incoming.chapterId <= 0
                || chapterRows == null) return reject("购买记录或现有账本读不到，不能写入");
        Purchase mine = null;
        boolean anotherPaid = false;
        for (Purchase row : chapterRows) {
            if (row == null || row.chapterId != incoming.chapterId || row.id <= 0) {
                return reject("现有章节账本不完整，不能写入");
            }
            if (row.accountId == incoming.accountId) {
                if (mine != null) return reject("同一账号已有重复账本，先核对订阅清单");
                mine = row;
            } else if (paid(incoming) && paid(row)) {
                anotherPaid = true;
                if (!imported && !Purchase.SRC_REMOTE_DETAIL.equals(incoming.source)) {
                    return reject(OTHER_PAID_MESSAGE);
                }
            }
        }
        if (mine != null) {
            if ((incoming.id == 0 || incoming.id == mine.id) && sameFact(mine, incoming)) {
                return new Decision(Action.KEEP, mine.id, "已有相同购买事实，原样保留");
            }
            return reject("这个号已有购买记录，不能覆盖金额、日期或来源；请核对订阅清单");
        }
        if (imported && anotherPaid) return reject(OTHER_PAID_MESSAGE);
        if (incoming.id != 0) return reject("新记录不能指定旧主键，撤销请使用核对留痕");
        if (incoming.costCoupons < 0 || incoming.costVouchers < 0 || incoming.purchasedAt <= 0) {
            return reject("金额或购买时间不明，不能编造购买记录");
        }
        return new Decision(Action.INSERT, 0, "可以新增购买事实");
    }

    public static boolean paid(Purchase purchase) {
        return purchase != null && (purchase.costCoupons > 0 || purchase.costVouchers > 0);
    }

    public static boolean sameFact(Purchase first, Purchase second) {
        return first != null && second != null && first.accountId == second.accountId
                && first.chapterId == second.chapterId && first.costCoupons == second.costCoupons
                && first.costVouchers == second.costVouchers && first.purchasedAt == second.purchasedAt
                && Objects.equals(first.source, second.source);
    }

    public static boolean samePurchase(Purchase first, Purchase second) {
        return first != null && second != null && first.id == second.id && sameFact(first, second);
    }

    /**
     * 2026-09-14 的真实跨号历史也可能先被记成 OWNED；完整明细提升的是本账号事实，
     * 其他号已付费不能否认它。普通补录仍不能借此覆盖旧金额。
     */
    public static boolean canPromoteOwned(Purchase stored, Purchase incoming, List<Purchase> chapterRows) {
        if (stored == null || incoming == null || chapterRows == null || stored.id <= 0
                || stored.accountId != incoming.accountId || stored.chapterId != incoming.chapterId
                || stored.costCoupons != 0 || stored.costVouchers != 0
                || !Purchase.SRC_OWNED.equals(stored.source) || incoming.id != 0
                || incoming.costCoupons != 0 || incoming.costVouchers <= 0 || incoming.purchasedAt <= 0
                || !Purchase.SRC_REMOTE_DETAIL.equals(incoming.source)) return false;
        int matched = 0;
        for (Purchase row : chapterRows) {
            if (row == null || row.id <= 0 || row.chapterId != stored.chapterId) return false;
            if (row.accountId == stored.accountId) {
                if (!samePurchase(row, stored)) return false;
                matched++;
            }
        }
        return matched == 1;
    }

    /** 删账比较所有列；目录价钱或远端章节 id 改过也不能继续使用旧证据。 */
    public static boolean sameChapters(List<Chapter> expected, List<Chapter> current) {
        if (expected == null || current == null || expected.size() != current.size()) return false;
        Map<Long, Chapter> rows = new HashMap<>();
        for (Chapter row : expected) {
            if (row == null || rows.put(row.id, row) != null) return false;
        }
        for (Chapter row : current) {
            Chapter old = row == null ? null : rows.remove(row.id);
            if (old == null || old.novelId != row.novelId || old.chapterNo != row.chapterNo
                    || old.priceCoupons != row.priceCoupons || !Objects.equals(old.title, row.title)
                    || !Objects.equals(old.volumeTitle, row.volumeTitle)
                    || !Objects.equals(old.sfChapterId, row.sfChapterId)) return false;
        }
        return rows.isEmpty();
    }

    /** 全书快照连 OWNED 与显示身份一起比，防止两次读屏之间归属或账号身份变了。 */
    public static boolean sameRows(List<PurchaseRow> expected, List<PurchaseRow> current) {
        if (expected == null || current == null || expected.size() != current.size()) return false;
        Map<Long, PurchaseRow> rows = new HashMap<>();
        for (PurchaseRow row : expected) {
            if (row == null || rows.put(row.purchaseId, row) != null) return false;
        }
        for (PurchaseRow row : current) {
            PurchaseRow old = row == null ? null : rows.remove(row.purchaseId);
            if (!sameRow(old, row)) return false;
        }
        return rows.isEmpty();
    }

    public static boolean sameRow(PurchaseRow first, PurchaseRow second) {
        return first != null && second != null && first.purchaseId == second.purchaseId
                && first.accountId == second.accountId && first.chapterId == second.chapterId
                && first.costCoupons == second.costCoupons && first.costVouchers == second.costVouchers
                && first.purchasedAt == second.purchasedAt && first.chapterNo == second.chapterNo
                && Objects.equals(first.source, second.source)
                && Objects.equals(first.chapterTitle, second.chapterTitle)
                && Objects.equals(first.novelTitle, second.novelTitle)
                && Objects.equals(first.accountLabel, second.accountLabel)
                && Objects.equals(first.accountNickname, second.accountNickname)
                && Objects.equals(first.accountLoginName, second.accountLoginName);
    }

    private static Decision reject(String message) {
        return new Decision(Action.REJECT, 0, message);
    }
}
