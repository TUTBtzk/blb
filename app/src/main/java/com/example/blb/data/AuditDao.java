package com.example.blb.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.example.blb.auto.RemoteLedgerRepair;
import com.example.blb.auto.SubscribedDetail;

import java.util.ArrayList;
import java.util.List;

/** 核对的证据单独存放，不能为了保存一次进度就碰到已有 purchase。 */
@Dao
public abstract class AuditDao {

    public static final class RepairResult {
        public final boolean ok;
        public final int deleted;
        public final String message;

        private RepairResult(boolean ok, int deleted, String message) {
            this.ok = ok;
            this.deleted = deleted;
            this.message = message;
        }
    }

    /** 每个号只认自己在这本书上的核对证据，不能借用上一个号的逐章结论。 */
    @Query("SELECT * FROM account_novel_audit "
            + "WHERE account_id = :accountId AND novel_id = :novelId LIMIT 1")
    public abstract AccountNovelAudit auditFor(long accountId, long novelId);

    /** 列表摘要要能直接说明哪些号核过，不能让用户逐个点进去找。 */
    @Query("SELECT * FROM account_novel_audit WHERE novel_id = :novelId ORDER BY account_id")
    public abstract List<AccountNovelAudit> loadProgressOfNovel(long novelId);

    /** 账号核完一个就让常驻摘要刷新，不要求用户点进日志才能知道进度。 */
    @Query("SELECT * FROM account_novel_audit WHERE novel_id = :novelId ORDER BY account_id")
    public abstract LiveData<List<AccountNovelAudit>> observeProgressOfNovel(long novelId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void saveProgress(AccountNovelAudit audit);

    /**
     * 免费 OWNED 不代表花过券，所以指纹只数两种实付列为正的行；它只供复用核对结论，
     * 金额被原地改过可能仍同指纹，不能拿它代替修账事务里的完整快照校验。
     */
    @Query("SELECT COUNT(*) || ':' || IFNULL(MAX(p.id), 0) FROM purchase p "
            + "INNER JOIN chapter c ON c.id = p.chapter_id "
            + "WHERE p.account_id = :accountId AND c.novel_id = :novelId "
            + "AND (p.cost_coupons > 0 OR p.cost_vouchers > 0)")
    public abstract String paidLedgerMarker(long accountId, long novelId);

    /** 留痕只能追加；相同主键意外重入时要报错，不能 REPLACE 掉原先的证据。 */
    @Insert
    public abstract long insertLedgerAudit(LedgerAudit audit);

    /** 撤销必须能找到当时那条证据，不能靠同章最近一条去猜。 */
    @Query("SELECT * FROM ledger_audit WHERE id = :auditId LIMIT 1")
    public abstract LedgerAudit ledgerAuditById(long auditId);

    /** 同一毫秒可能补记多章，按 id 再排一次才能让常驻摘要与详情顺序一致。 */
    @Query("SELECT * FROM ledger_audit WHERE novel_id = :novelId "
            + "ORDER BY at DESC, id DESC LIMIT :limit")
    public abstract List<LedgerAudit> loadRecentLedgerAudits(long novelId, int limit);

    /** 删除与撤销的留痕都必须当场出现在核对列表，不能等页面重开。 */
    @Query("SELECT * FROM ledger_audit WHERE novel_id = :novelId "
            + "ORDER BY at DESC, id DESC LIMIT :limit")
    public abstract LiveData<List<LedgerAudit>> observeRecentLedgerAudits(long novelId, int limit);

    /** 免费 OWNED 也可能暴露归属冲突，删账的第二份快照不能只取付费行。 */
    @Query("SELECT p.id AS purchaseId, p.account_id AS accountId, p.chapter_id AS chapterId, "
            + "p.cost_coupons AS costCoupons, p.cost_vouchers AS costVouchers, "
            + "p.purchased_at AS purchasedAt, p.source AS source, "
            + "c.chapter_no AS chapterNo, c.title AS chapterTitle, n.title AS novelTitle, "
            + "a.label AS accountLabel, a.nickname AS accountNickname, a.login_name AS accountLoginName "
            + "FROM purchase p JOIN chapter c ON c.id = p.chapter_id "
            + "JOIN novel n ON n.id = c.novel_id JOIN account a ON a.id = p.account_id "
            + "WHERE c.novel_id = :novelId ORDER BY c.chapter_no ASC, a.id ASC")
    public abstract List<PurchaseRow> loadAllRowsOfNovel(long novelId);

    /** 保留上次真读到的时间供人看，清空指纹强制重读，不能把旧证据签给改过的账本。 */
    @Query("UPDATE account_novel_audit SET ledger_marker = NULL WHERE novel_id = :novelId")
    public abstract void invalidateNovel(long novelId);

    /** 导入可能一次碰多本书，旧的逐章结论都不能继续当成当前账本的证明。 */
    @Query("UPDATE account_novel_audit SET ledger_marker = NULL")
    public abstract void invalidateAll();

    /** 核对修复只失效这个号，不能把前面已核实的其他号一起清掉。 */
    @Query("UPDATE account_novel_audit SET ledger_marker = NULL "
            + "WHERE account_id = :accountId AND novel_id = :novelId")
    public abstract void invalidateAccount(long accountId, long novelId);

    @Query("SELECT * FROM chapter WHERE novel_id = :novelId ORDER BY chapter_no ASC")
    protected abstract List<Chapter> currentChapters(long novelId);

    @Query("SELECT * FROM purchase WHERE id = :id")
    protected abstract Purchase purchaseById(long id);

    @Query("SELECT * FROM purchase WHERE chapter_id = :chapterId")
    protected abstract List<Purchase> purchasesOfChapter(long chapterId);

    @Query("SELECT * FROM chapter WHERE id = :id")
    protected abstract Chapter chapterById(long id);

    @Query("SELECT * FROM account WHERE id = :id")
    protected abstract Account accountById(long id);

    @Query("SELECT * FROM ledger_audit WHERE kind = 'RESTORE' AND account_id = :accountId "
            + "AND novel_id = :novelId AND chapter_id = :chapterId ORDER BY id DESC")
    protected abstract List<LedgerAudit> restoresOfChapter(long accountId, long novelId, long chapterId);

    @Query("DELETE FROM purchase WHERE id = :id")
    protected abstract int deleteVerifiedPurchase(long id);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract long insertRestoredPurchase(Purchase purchase);

    @Transaction
    public boolean snapshotMatches(long novelId, List<Chapter> expectedChapters,
                                   List<PurchaseRow> expectedPaidRows, List<PurchaseRow> expectedAllRows) {
        List<PurchaseRow> current = loadAllRowsOfNovel(novelId);
        return LedgerWritePolicy.sameChapters(expectedChapters, currentChapters(novelId))
                && LedgerWritePolicy.sameRows(expectedAllRows, current)
                && LedgerWritePolicy.sameRows(expectedPaidRows, paidRows(current));
    }

    /** 两次读屏在事务外完成；事务只复核快照与证据并落账，不能持着数据库锁去等屏幕滚动。 */
    @Transaction
    public RepairResult commitDeletion(RemoteLedgerRepair.Plan first, RemoteLedgerRepair.Plan second,
                                       List<Chapter> expectedChapters, List<PurchaseRow> expectedPaidRows,
                                       List<PurchaseRow> expectedAllRows, long now) {
        RemoteLedgerRepair.Plan context = second != null ? second : first;
        if (first == null || second == null || !first.ok || !second.ok
                || !RemoteLedgerRepair.sameDeletionPlan(first, second)) {
            return suspect(context, now, "两次完整订阅明细未形成相同删除证据");
        }
        if (now < first.plannedAt || now < second.plannedAt
                || !LedgerWritePolicy.sameChapters(first.chapters, expectedChapters)
                || !LedgerWritePolicy.sameChapters(second.chapters, expectedChapters)
                || !LedgerWritePolicy.sameRows(first.allRowsOfNovel, expectedAllRows)
                || !LedgerWritePolicy.sameRows(second.allRowsOfNovel, expectedAllRows)
                || !snapshotMatches(second.novelId, expectedChapters, expectedPaidRows, expectedAllRows)) {
            return suspect(context, now, "核对之后目录或账本已变化，旧证据不能继续删账");
        }
        List<Chapter> chapters = currentChapters(second.novelId);
        List<PurchaseRow> allRows = loadAllRowsOfNovel(second.novelId);
        String who = second.who;
        String book = second.book;
        RemoteLedgerRepair.Plan rechecked = RemoteLedgerRepair.deletionPlan(who, book,
                second.accountId, second.aggregate, second.readResult, chapters, allRows, now);
        if (!rechecked.ok || !RemoteLedgerRepair.sameDeletionPlan(second, rechecked)) {
            return suspect(context, now, "提交前复核没有通过：" + rechecked.message);
        }
        List<Purchase> originals = new ArrayList<>();
        List<LedgerAudit> deletionEvents = new ArrayList<>();
        for (PurchaseRow candidate : second.deletions) {
            Purchase original = purchaseById(candidate.purchaseId);
            if (!LedgerWritePolicy.samePurchase(original, purchaseFrom(candidate))
                    || Purchase.SRC_OWNED.equals(original.source)
                    || original.costCoupons < 0 || original.costVouchers < 0
                    || original.purchasedAt <= 0 || now <= original.purchasedAt
                    || now - original.purchasedAt <= SubscribedDetail.FRESH_MS) {
                return suspect(context, now, "待修正原记录已变化、金额未知或仍在清单刷新等待期");
            }
            for (Purchase other : purchasesOfChapter(original.chapterId)) {
                if (other.accountId != original.accountId) {
                    return suspect(context, now, "这一章在别的号名下也有记录，不自动删（包括免费归属）");
                }
            }
            String message = who + " 在《" + book + "》的第 " + candidate.chapterNo
                    + " 章，两次完整明细均无此记录，已修正账本；可以撤销";
            LedgerAudit event = event(LedgerAudit.KIND_DELETE, second.novelId, candidate, message, now, 0);
            // 只保存一串不可重建的 JSON 不算可撤销，必须在删第一条之前证明整批留痕能还原。
            if (!LedgerWritePolicy.samePurchase(original, LedgerAuditPayload.parsedPurchase(event.detail))) {
                return suspect(context, now, "原记录无法完整编码为撤销留痕");
            }
            originals.add(original);
            deletionEvents.add(event);
        }
        for (int index = 0; index < originals.size(); index++) {
            Purchase original = originals.get(index);
            insertLedgerAudit(deletionEvents.get(index));
            if (deleteVerifiedPurchase(original.id) != 1) {
                throw new IllegalStateException("删除原记录时账本变化，整批修正已回滚");
            }
        }
        List<PurchaseRow> remaining = new ArrayList<>();
        for (PurchaseRow row : expectedAllRows) {
            boolean removed = false;
            for (Purchase original : originals) if (original.id == row.purchaseId) removed = true;
            if (!removed) remaining.add(row);
        }
        if (!LedgerWritePolicy.sameRows(remaining, loadAllRowsOfNovel(second.novelId))) {
            throw new IllegalStateException("修正后账本复核不一致，整批修正已回滚");
        }
        invalidateAccount(second.accountId, second.novelId);
        return new RepairResult(true, originals.size(), "账本修正 " + originals.size() + " 条，已留下可撤销记录");
    }

    /** 由原补记事务在购买行落库后调用，补记与留痕必须一起提交或一起回滚。 */
    public long recordBackfill(long novelId, PurchaseRow restored, String message, long now) {
        if (restored == null || restored.purchaseId <= 0) {
            throw new IllegalArgumentException("补记留痕缺少已经落库的原记录");
        }
        long id = insertLedgerAudit(event(LedgerAudit.KIND_BACKFILL, novelId, restored, message, now, 0));
        invalidateAccount(restored.accountId, novelId);
        return id;
    }

    @Transaction
    public String restore(long auditId) {
        LedgerAudit deleted = ledgerAuditById(auditId);
        if (deleted == null || !LedgerAudit.KIND_DELETE.equals(deleted.kind)) {
            return "不能撤销：没有找到对应的账本修正记录";
        }
        Purchase original = LedgerAuditPayload.parsedPurchase(deleted.detail);
        if (original == null || original.accountId != deleted.accountId || original.chapterId != deleted.chapterId
                || LedgerAuditPayload.deleteAuditId(deleted.detail) != 0) {
            return "不能撤销：原始记录留痕不完整";
        }
        for (LedgerAudit restored : restoresOfChapter(deleted.accountId, deleted.novelId, deleted.chapterId)) {
            if (LedgerAuditPayload.deleteAuditId(restored.detail) != auditId) continue;
            if (!LedgerWritePolicy.samePurchase(original, LedgerAuditPayload.parsedPurchase(restored.detail))) {
                return "不能再次撤销：之前的撤销留痕与原记录不一致";
            }
            return LedgerWritePolicy.samePurchase(original, purchaseById(original.id))
                    ? "这条修正已经撤销，原订阅记录仍在"
                    : "这条修正已经撤销过，但账本后来又有变化，请重新核对订阅清单";
        }
        if (purchaseById(original.id) != null) return "不能撤销：原主键已有记录，不能覆盖现有账本";
        Chapter chapter = chapterById(original.chapterId);
        if (accountById(original.accountId) == null || chapter == null || chapter.novelId != deleted.novelId) {
            return "不能撤销：原账号或原章节已不存在";
        }
        for (Purchase row : purchasesOfChapter(original.chapterId)) {
            if (row.accountId == original.accountId) return "不能撤销：这个号已有该章记录，不能覆盖";
            if (LedgerWritePolicy.paid(original) && LedgerWritePolicy.paid(row)) {
                return "不能撤销：该章已有其他账号付费，恢复会造成重复归属";
            }
        }
        insertRestoredPurchase(original);
        LedgerAudit restored = new LedgerAudit();
        restored.at = System.currentTimeMillis();
        restored.accountId = original.accountId;
        restored.novelId = deleted.novelId;
        restored.kind = LedgerAudit.KIND_RESTORE;
        restored.chapterId = chapter.id;
        restored.chapterNo = chapter.chapterNo;
        restored.title = chapter.title;
        String message = "已撤销修正：恢复第 " + chapter.chapterNo + " 章的原订阅记录";
        restored.detail = LedgerAuditPayload.encode(message, original, auditId);
        insertLedgerAudit(restored);
        if (!LedgerWritePolicy.samePurchase(original, purchaseById(original.id))) {
            throw new IllegalStateException("撤销后的原记录校验失败，已回滚");
        }
        invalidateNovel(deleted.novelId);
        return message;
    }

    private RepairResult suspect(RemoteLedgerRepair.Plan plan, long now, String reason) {
        String message = reason + "，已转存疑，未删除任何记录";
        LedgerAudit audit = new LedgerAudit();
        audit.at = now;
        audit.accountId = plan == null ? -1 : plan.accountId;
        audit.novelId = plan == null ? -1 : plan.novelId;
        audit.kind = LedgerAudit.KIND_SUSPECT;
        audit.detail = LedgerAuditPayload.encode(message, null, 0);
        insertLedgerAudit(audit);
        if (plan != null) invalidateAccount(plan.accountId, plan.novelId);
        return new RepairResult(false, 0, message);
    }

    private static List<PurchaseRow> paidRows(List<PurchaseRow> rows) {
        List<PurchaseRow> paid = new ArrayList<>();
        for (PurchaseRow row : rows) if (row.costCoupons > 0 || row.costVouchers > 0) paid.add(row);
        return paid;
    }

    private static Purchase purchaseFrom(PurchaseRow row) {
        if (row == null) return null;
        Purchase purchase = new Purchase();
        purchase.id = row.purchaseId;
        purchase.accountId = row.accountId;
        purchase.chapterId = row.chapterId;
        purchase.costCoupons = row.costCoupons;
        purchase.costVouchers = row.costVouchers;
        purchase.purchasedAt = row.purchasedAt;
        purchase.source = row.source;
        return purchase;
    }

    private static LedgerAudit event(String kind, long novelId, PurchaseRow row,
                                     String message, long now, long deletedAuditId) {
        LedgerAudit audit = new LedgerAudit();
        audit.at = now;
        audit.accountId = row.accountId;
        audit.novelId = novelId;
        audit.kind = kind;
        audit.chapterId = row.chapterId;
        audit.chapterNo = row.chapterNo;
        audit.title = row.chapterTitle;
        audit.detail = LedgerAuditPayload.encode(message, purchaseFrom(row), deletedAuditId);
        return audit;
    }

    /** 扫描时间与章数成对更新，免得新时间配着旧章数，让过期目录看起来刚核实过。 */
    @Query("UPDATE novel SET catalog_scanned_at = :scannedAt, "
            + "catalog_chapter_count = :chapterCount WHERE id = :novelId")
    public abstract int setCatalogScan(long novelId, long scannedAt, int chapterCount);
}
