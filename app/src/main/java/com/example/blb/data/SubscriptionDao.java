package com.example.blb.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.util.List;
import java.util.Map;

@Dao
public interface SubscriptionDao {

    // ---------- 小说 ----------

    @Query("SELECT * FROM novel ORDER BY is_target DESC, title ASC")
    LiveData<List<Novel>> observeNovels();

    @Query("SELECT * FROM novel ORDER BY is_target DESC, title ASC")
    List<Novel> loadNovels();

    @Query("SELECT * FROM novel WHERE id = :id")
    Novel novelById(long id);

    @Query("SELECT * FROM novel WHERE is_target = 1 LIMIT 1")
    Novel targetNovel();

    /** CSV 导入按书名对号；书名重复的情况本 App 不区分，按第一本算。 */
    @Query("SELECT * FROM novel WHERE title = :title LIMIT 1")
    Novel novelByTitle(String title);

    @Insert
    long insertNovel(Novel novel);

    @Update
    void updateNovel(Novel novel);

    @Delete
    void deleteEmptyNovelRow(Novel novel);

    /** 级联删除小说会把真实购买也抹掉，让它再次进入待买队列；只允许删没有账的空登记。 */
    @Transaction
    default void deleteNovel(Novel novel) {
        if (novel == null || novel.id <= 0) throw new IllegalArgumentException("小说记录不明");
        if (!loadPurchasesOfNovel(novel.id).isEmpty()) {
            throw new IllegalStateException("这本书已有订阅账本，不能删除；请先核对订阅清单");
        }
        deleteEmptyNovelRow(novel);
    }

    @Query("UPDATE novel SET is_target = 0")
    void clearTarget();

    @Query("UPDATE novel SET is_target = 1 WHERE id = :id")
    void markTarget(long id);

    /** 集中订阅目标同一时间只有一本。 */
    @Transaction
    default void setTargetNovel(long id) {
        clearTarget();
        markTarget(id);
    }

    // ---------- 章节 ----------

    @Query("SELECT * FROM chapter WHERE novel_id = :novelId ORDER BY chapter_no ASC")
    LiveData<List<Chapter>> observeChapters(long novelId);

    @Query("SELECT * FROM chapter WHERE novel_id = :novelId ORDER BY chapter_no ASC")
    List<Chapter> loadChapters(long novelId);

    @Query("SELECT * FROM chapter WHERE id = :id")
    Chapter chapterById(long id);

    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND chapter_no = :chapterNo LIMIT 1")
    Chapter chapterByNo(long novelId, int chapterNo);

    @Query("SELECT IFNULL(MAX(chapter_no), 0) FROM chapter WHERE novel_id = :novelId")
    int maxChapterNo(long novelId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertChapter(Chapter chapter);

    @Update
    void updateChapter(Chapter chapter);

    @Delete
    void deleteEmptyChapterRow(Chapter chapter);

    /** 免费回填也决定下一章，不能借删章节绕过核对证据把任何 purchase 级联清掉。 */
    @Transaction
    default void deleteChapter(Chapter chapter) {
        if (chapter == null || chapter.id <= 0) throw new IllegalArgumentException("章节记录不明");
        if (!loadPurchasesForChapters(java.util.Collections.singletonList(chapter.id)).isEmpty()) {
            throw new IllegalStateException("这一章已有订阅记录，不能删除；请核对订阅清单");
        }
        deleteEmptyChapterRow(chapter);
    }

    @Query("UPDATE chapter SET chapter_no = :chapterNo WHERE id = :id")
    void setChapterNo(long id, int chapterNo);

    @Query("DELETE FROM chapter WHERE id = :id")
    void deleteEmptyChapterById(long id);

    /** 作者删章时也只能移除空登记，目录搬号不能成为另一条无留痕删账通路。 */
    @Transaction
    default void deleteChapterById(long id) {
        if (id <= 0) throw new IllegalArgumentException("章节记录不明");
        if (!loadPurchasesForChapters(java.util.Collections.singletonList(id)).isEmpty()) {
            throw new IllegalStateException("作者目录里消失的章节仍有订阅账本，停止重排并核对");
        }
        deleteEmptyChapterById(id);
    }

    /**
     * 作者动过目录之后，把账本里的章号整体搬到跟界面一致。
     *
     * <p><b>为什么要两段走</b>：(novel_id, chapter_no) 是唯一索引，第 68 章搬到 69 的时候
     * 69 还被老的第 69 章占着 —— 一条一条改必然撞索引。所以先把所有要搬的章改成<b>负数</b>
     * （负号跟正的章号永不重号，负数之间也各不相同），再一次性改回正数。
     *
     * <p>购买记录挂在 {@code chapter.id} 上，章号只是这一章现在排第几 —— 搬号不动任何一条
     * 购买记录，「哪个号买过哪一章」原样保留。
     */
    @Transaction
    default void realign(Map<Long, Integer> renumber, List<Long> dropIds, int newStartChapterNo,
                         long novelId) {
        if (dropIds != null) {
            for (Long id : dropIds) {
                if (id != null) deleteChapterById(id);
            }
        }
        if (renumber != null && !renumber.isEmpty()) {
            for (Map.Entry<Long, Integer> e : renumber.entrySet()) {
                setChapterNo(e.getKey(), -e.getValue());
            }
            for (Map.Entry<Long, Integer> e : renumber.entrySet()) {
                setChapterNo(e.getKey(), e.getValue());
            }
        }
        if (newStartChapterNo > 0) setStartChapter(novelId, newStartChapterNo);
    }

    /** 已存在 (novelId, chapterNo) 时返回既有行，避免唯一约束报错。 */
    @Transaction
    default Chapter ensureChapter(long novelId, int chapterNo, String title, int price) {
        Chapter existing = chapterByNo(novelId, chapterNo);
        if (existing != null) return existing;
        Chapter c = new Chapter();
        c.novelId = novelId;
        c.chapterNo = chapterNo;
        c.title = title;
        c.priceCoupons = price;
        long id = insertChapter(c);
        return id > 0 ? chapterById(id) : chapterByNo(novelId, chapterNo);
    }

    // ---------- 购买记录 ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insertPurchaseOrAbort(Purchase purchase);

    /** 手填和自动购买按代码确定的来源写入；REPLACE 会改掉旧金额与主键，不能再当作补录。 */
    @Transaction
    default long upsertPurchase(Purchase purchase) {
        return writePurchase(purchase, false);
    }

    /** 2026-09-14 的跨号补记例外必须有远端明细；CSV 的原 source 只存作事实，不能充当权限。 */
    @Transaction
    default long upsertImportedPurchase(Purchase purchase) {
        return writePurchase(purchase, true);
    }

    private long writePurchase(Purchase purchase, boolean imported) {
        List<Purchase> existing = purchase == null ? null
                : loadPurchasesForChapters(java.util.Collections.singletonList(purchase.chapterId));
        LedgerWritePolicy.Decision decision = imported
                ? LedgerWritePolicy.decideImported(purchase, existing)
                : LedgerWritePolicy.decide(purchase, existing);
        if (decision.action == LedgerWritePolicy.Action.REJECT) {
            throw new IllegalStateException(decision.message);
        }
        if (decision.action == LedgerWritePolicy.Action.KEEP) return decision.existingId;
        return insertPurchaseOrAbort(purchase);
    }

    /**
     * 2026-09-14 真实跨号订阅被误拒；远端恢复保留服务器已发生的事实，
     * 但币种、金额必须明确，同账号仍不能覆盖或重复插入。
     */
    @Transaction
    default long insertRemotePurchase(Purchase purchase) {
        if (purchase == null || !Purchase.SRC_REMOTE_DETAIL.equals(purchase.source)
                || purchase.costCoupons != 0 || purchase.costVouchers <= 0) {
            throw new IllegalArgumentException("远端补账必须有确定的代券购买事实");
        }
        return upsertPurchase(purchase);
    }

    @Query("UPDATE purchase SET cost_coupons = 0, cost_vouchers = :vouchers, "
            + "purchased_at = :purchasedAt, source = 'REMOTE_DETAIL' "
            + "WHERE id = :id AND account_id = :accountId AND chapter_id = :chapterId "
            + "AND cost_coupons = 0 AND cost_vouchers = 0 AND source = 'OWNED'")
    int promoteOwnedPurchaseRow(long id, long accountId, long chapterId, int vouchers,
                                long purchasedAt);

    @Transaction
    default int promoteOwnedPurchase(long id, long accountId, long chapterId, int vouchers,
                                     long purchasedAt) {
        List<Purchase> rows = loadPurchasesForChapters(java.util.Collections.singletonList(chapterId));
        Purchase stored = purchaseOf(rows, accountId);
        Purchase incoming = new Purchase();
        incoming.accountId = accountId;
        incoming.chapterId = chapterId;
        incoming.costVouchers = vouchers;
        incoming.purchasedAt = purchasedAt;
        incoming.source = Purchase.SRC_REMOTE_DETAIL;
        if (stored == null || stored.id != id || !LedgerWritePolicy.canPromoteOwned(stored, incoming, rows)) {
            return 0;
        }
        return promoteOwnedPurchaseRow(id, accountId, chapterId, vouchers, purchasedAt);
    }

    /**
     * 逐章远端明细核实后的历史账一次性补回。恢复专用写入绝不使用 REPLACE：
     * 已有事实只能原样幂等重放，或把本账号零金额 OWNED 原地提升为远端付费事实。
     */
    @Transaction
    default boolean restoreRemotePurchases(long accountId, long novelId,
                                           List<Purchase> purchases,
                                           List<Chapter> plannedChapters,
                                           List<PurchaseRow> plannedPaidRows) {
        if (!sameChapterSnapshot(plannedChapters, loadChapters(novelId))
                || !samePaidSnapshot(plannedPaidRows, loadPaidRowsOfNovel(novelId))) {
            return false;
        }
        return applyRemotePurchases(accountId, novelId, purchases,
                plannedChapters, loadPurchasesForChapters(chapterIds(purchases)));
    }

    /** 只供上面的快照校验入口在同一个 Room 事务里调用，不对业务层暴露绕过校验的重载。 */
    private boolean applyRemotePurchases(long accountId, long novelId,
                                         List<Purchase> purchases,
                                         List<Chapter> chapters,
                                         List<Purchase> affectedPurchases) {
        if (purchases == null || purchases.isEmpty()) return false;
        Map<Long, Chapter> chapterById = new java.util.HashMap<>();
        if (chapters != null) {
            for (Chapter chapter : chapters) {
                if (chapter == null || chapterById.put(chapter.id, chapter) != null) return false;
            }
        }
        Map<Long, List<Purchase>> existing = purchasesByChapter(affectedPurchases);
        java.util.Set<Long> incomingChapters = new java.util.HashSet<>();
        int expectedVoucherTotal = 0;
        for (Purchase incoming : purchases) {
            if (incoming == null || !incomingChapters.add(incoming.chapterId)
                    || incoming.id != 0 || incoming.accountId != accountId
                    || incoming.costCoupons != 0 || incoming.costVouchers <= 0
                    || incoming.purchasedAt <= 0
                    || !Purchase.SRC_REMOTE_DETAIL.equals(incoming.source)) return false;
            expectedVoucherTotal += incoming.costVouchers;
            Chapter chapter = chapterById.get(incoming.chapterId);
            if (chapter == null || chapter.novelId != novelId) return false;
            List<Purchase> rows = existing.get(incoming.chapterId);
            if (rows == null) continue;
            Purchase mine = null;
            for (Purchase old : rows) {
                if (old.accountId != accountId) continue;
                if (mine != null) return false;
                mine = old;
            }
            if (mine != null && !sameRemoteFact(mine, incoming) && !promotableOwned(mine)) {
                return false;
            }
        }
        for (Purchase incoming : purchases) {
            Purchase mine = purchaseOf(existing.get(incoming.chapterId), accountId);
            if (mine == null) {
                insertRemotePurchase(incoming);
            } else if (promotableOwned(mine)) {
                if (promoteOwnedPurchase(mine.id, accountId, incoming.chapterId,
                        incoming.costVouchers, incoming.purchasedAt) != 1) {
                    throw new IllegalStateException("远端补账时 OWNED 状态已变化");
                }
            }
        }
        Map<Long, List<Purchase>> after = purchasesByChapter(
                loadPurchasesForChapters(new java.util.ArrayList<>(incomingChapters)));
        int verified = 0;
        int verifiedVoucherTotal = 0;
        for (Purchase incoming : purchases) {
            Purchase mine = purchaseOf(after.get(incoming.chapterId), accountId);
            if (mine == null || !sameRemoteFact(mine, incoming)) {
                throw new IllegalStateException("远端补账提交前复核失败");
            }
            verified++;
            verifiedVoucherTotal += mine.costVouchers;
        }
        if (verified != purchases.size() || verifiedVoucherTotal != expectedVoucherTotal) {
            throw new IllegalStateException("远端补账提交前章数或代券合计复核失败");
        }
        return true;
    }

    private static List<Long> chapterIds(List<Purchase> purchases) {
        List<Long> ids = new java.util.ArrayList<>();
        if (purchases != null) {
            for (Purchase purchase : purchases) {
                if (purchase != null && !ids.contains(purchase.chapterId)) ids.add(purchase.chapterId);
            }
        }
        return ids;
    }

    private static Map<Long, List<Purchase>> purchasesByChapter(List<Purchase> purchases) {
        Map<Long, List<Purchase>> out = new java.util.HashMap<>();
        if (purchases == null) return out;
        for (Purchase purchase : purchases) {
            if (purchase == null) continue;
            List<Purchase> rows = out.get(purchase.chapterId);
            if (rows == null) {
                rows = new java.util.ArrayList<>();
                out.put(purchase.chapterId, rows);
            }
            rows.add(purchase);
        }
        return out;
    }

    static boolean sameChapterSnapshot(List<Chapter> planned, List<Chapter> current) {
        if (planned == null || current == null || planned.size() != current.size()) return false;
        Map<Long, Chapter> expected = new java.util.HashMap<>();
        for (Chapter chapter : planned) {
            if (chapter == null || expected.put(chapter.id, chapter) != null) return false;
        }
        for (Chapter chapter : current) {
            Chapter old = chapter == null ? null : expected.remove(chapter.id);
            if (old == null || old.novelId != chapter.novelId
                    || old.chapterNo != chapter.chapterNo
                    || !java.util.Objects.equals(old.title, chapter.title)
                    || !java.util.Objects.equals(old.volumeTitle, chapter.volumeTitle)) return false;
        }
        return expected.isEmpty();
    }

    static boolean samePaidSnapshot(List<PurchaseRow> planned, List<PurchaseRow> current) {
        if (planned == null || current == null || planned.size() != current.size()) return false;
        Map<Long, PurchaseRow> expected = new java.util.HashMap<>();
        for (PurchaseRow row : planned) {
            if (row == null || expected.put(row.purchaseId, row) != null) return false;
        }
        for (PurchaseRow row : current) {
            PurchaseRow old = row == null ? null : expected.remove(row.purchaseId);
            if (old == null || old.accountId != row.accountId || old.chapterId != row.chapterId
                    || old.costCoupons != row.costCoupons || old.costVouchers != row.costVouchers
                    || old.purchasedAt != row.purchasedAt
                    || !java.util.Objects.equals(old.source, row.source)) return false;
        }
        return expected.isEmpty();
    }

    static Purchase purchaseOf(List<Purchase> rows, long accountId) {
        if (rows == null) return null;
        for (Purchase row : rows) if (row.accountId == accountId) return row;
        return null;
    }

    static boolean promotableOwned(Purchase row) {
        return row != null && row.costCoupons == 0 && row.costVouchers == 0
                && Purchase.SRC_OWNED.equals(row.source);
    }

    static boolean sameRemoteFact(Purchase stored, Purchase incoming) {
        if (stored == null || incoming == null) return false;
        if (stored.accountId != incoming.accountId || stored.chapterId != incoming.chapterId
                || stored.costCoupons != 0 || stored.costVouchers != incoming.costVouchers
                || !Purchase.SRC_REMOTE_DETAIL.equals(stored.source)) return false;
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        a.setTimeInMillis(stored.purchasedAt);
        b.setTimeInMillis(incoming.purchasedAt);
        return stored.purchasedAt > 0 && incoming.purchasedAt > 0
                && a.get(java.util.Calendar.ERA) == b.get(java.util.Calendar.ERA)
                && a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** 删除会让这章重新变成待买；只有 AuditDao 的两次证据校验事务才允许执行。 */
    default void deletePurchase(Purchase purchase) {
        throw new IllegalStateException("订阅记录不能直接删除，请用「核对订阅清单」更正并留下可撤销记录");
    }

    @Query("SELECT * FROM purchase WHERE chapter_id IN (:chapterIds)")
    List<Purchase> loadPurchasesForChapters(List<Long> chapterIds);

    @Query("SELECT * FROM purchase WHERE chapter_id IN "
            + "(SELECT id FROM chapter WHERE novel_id = :novelId)")
    List<Purchase> loadPurchasesOfNovel(long novelId);

    @Query("SELECT * FROM purchase WHERE chapter_id IN "
            + "(SELECT id FROM chapter WHERE novel_id = :novelId)")
    LiveData<List<Purchase>> observePurchasesOfNovel(long novelId);

    @Query("SELECT COUNT(*) FROM purchase WHERE account_id = :accountId AND chapter_id = :chapterId")
    int countRealPurchase(long accountId, long chapterId);

    /**
     * 这个号在这本书上「花过券」的记录条数 —— 拿去和「我的 → 代券 → 订阅清单」里那一行的
     * 「N章节」对账（见 {@code auto.VoucherLedger}）。
     *
     * <p>为什么按「花过券」筛而不是按 source：清单只数<b>付费订阅</b>。免费章记的是
     * {@code OWNED}、花费 0，服务器不会把它算进「2章节」。
     * 旧记录（v4 之前）代券列一律是 0、花费记在火券列上，所以两列都要看。
     */
    @Query("SELECT COUNT(*) FROM purchase WHERE account_id = :accountId "
            + "AND chapter_id IN (SELECT id FROM chapter WHERE novel_id = :novelId) "
            + "AND (cost_coupons > 0 OR cost_vouchers > 0)")
    int countPaidPurchases(long accountId, long novelId);

    /**
     * 账本自己记的火券花费合计。正常必须是 0 —— 用户不充值火券，
     * 而清单那一行也该写着「0火券」。两边有一边不是 0 就说明有一边记错了。
     */
    @Query("SELECT CAST(IFNULL(SUM(cost_coupons), 0) AS INTEGER) FROM purchase "
            + "WHERE account_id = :accountId "
            + "AND chapter_id IN (SELECT id FROM chapter WHERE novel_id = :novelId)")
    int sumFireSpent(long accountId, long novelId);

    /**
     * 这本书里「有号买过」的章。作者动过目录、要按标题重新对号的时候用它 ——
     * 这些章绝不许被当成空登记删掉，删了就会被重新买一遍，那是真花钱。
     */
    @Query("SELECT DISTINCT chapter_id FROM purchase "
            + "WHERE chapter_id IN (SELECT id FROM chapter WHERE novel_id = :novelId)")
    List<Long> realPurchasedChapterIds(long novelId);

    @Query("SELECT p.id AS purchaseId, p.account_id AS accountId, p.chapter_id AS chapterId, "
            + "p.cost_coupons AS costCoupons, p.cost_vouchers AS costVouchers, "
            + "p.purchased_at AS purchasedAt, p.source AS source, "
            + "c.chapter_no AS chapterNo, c.title AS chapterTitle, n.title AS novelTitle, "
            + "a.label AS accountLabel, a.nickname AS accountNickname, a.login_name AS accountLoginName "
            + "FROM purchase p "
            + "JOIN chapter c ON c.id = p.chapter_id "
            + "JOIN novel n ON n.id = c.novel_id "
            + "JOIN account a ON a.id = p.account_id "
            + "ORDER BY p.purchased_at DESC LIMIT :limit")
    LiveData<List<PurchaseRow>> observeRecentRows(int limit);

    @Query("SELECT p.id AS purchaseId, p.account_id AS accountId, p.chapter_id AS chapterId, "
            + "p.cost_coupons AS costCoupons, p.cost_vouchers AS costVouchers, "
            + "p.purchased_at AS purchasedAt, p.source AS source, "
            + "c.chapter_no AS chapterNo, c.title AS chapterTitle, n.title AS novelTitle, "
            + "a.label AS accountLabel, a.nickname AS accountNickname, a.login_name AS accountLoginName "
            + "FROM purchase p "
            + "JOIN chapter c ON c.id = p.chapter_id "
            + "JOIN novel n ON n.id = c.novel_id "
            + "JOIN account a ON a.id = p.account_id "
            + "ORDER BY n.title ASC, c.chapter_no ASC, a.id ASC")
    List<PurchaseRow> loadAllRows();

    /**
     * 这本书上<b>所有号</b>「花过券」的记录，逐条带着章号、标题、花费和买入时间 ——
     * 拿去跟「我的 → 代券 → 订阅清单 →（点整行）→ 订阅明细」那一页<b>逐章</b>对账
     * （见 {@code auto.SubscribedDetail}）。
     *
     * <p>2026-09-14 用户确认多号订同章是真实历史；全书快照用于防止核对期间事实变化，
     * 并说明还有哪些号订过。跨号重复本身不是错账，当前号的明细和账本仍须逐章完全对应。
     *
     * <p>筛「花过券」的口径和 {@link #countPaidPurchases} 一致：免费章（{@code OWNED}、
     * 花费 0）不算 —— 明细页只列付费订阅。
     */
    @Query("SELECT p.id AS purchaseId, p.account_id AS accountId, p.chapter_id AS chapterId, "
            + "p.cost_coupons AS costCoupons, p.cost_vouchers AS costVouchers, "
            + "p.purchased_at AS purchasedAt, p.source AS source, "
            + "c.chapter_no AS chapterNo, c.title AS chapterTitle, n.title AS novelTitle, "
            + "a.label AS accountLabel, a.nickname AS accountNickname, a.login_name AS accountLoginName "
            + "FROM purchase p "
            + "JOIN chapter c ON c.id = p.chapter_id "
            + "JOIN novel n ON n.id = c.novel_id "
            + "JOIN account a ON a.id = p.account_id "
            + "WHERE c.novel_id = :novelId "
            + "AND (p.cost_coupons > 0 OR p.cost_vouchers > 0) "
            + "ORDER BY c.chapter_no ASC, a.id ASC")
    List<PurchaseRow> loadPaidRowsOfNovel(long novelId);

    // ---------- 建议 ----------

    /** 所有账号都还没买过的最小章节。 */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND id NOT IN "
            + "(SELECT chapter_id FROM purchase) "
            + "ORDER BY chapter_no ASC LIMIT 1")
    Chapter findNextUnownedChapter(long novelId);

    /**
     * 界面上「待订阅」那一小段用的，跟 {@link #findNextUnownedChapter} 同一套条件，
     * 只是一次取多条。
     */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND id NOT IN "
            + "(SELECT chapter_id FROM purchase) "
            + "ORDER BY chapter_no ASC LIMIT :limit")
    List<Chapter> findUnownedChapters(long novelId, int limit);

    /**
     * 同上，但只看「起始章」之后的章。你指定从第 N 章开始订阅时，前面那些旧章
     * 就算没人买也不该动 —— 券花在那儿不会让你更早看到新章。
     */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND chapter_no >= :fromNo "
            + "AND id NOT IN (SELECT chapter_id FROM purchase) "
            + "ORDER BY chapter_no ASC LIMIT :limit")
    List<Chapter> findUnownedChaptersFrom(long novelId, int fromNo, int limit);

    /**
     * 自动订阅那一轮要走的<b>整条队列</b>：起始章之后所有还没人买过的章，按章号从小到大，
     * <b>不设条数上限</b>。
     *
     * <p>为什么不带 limit：停止条件是「这个号的代券花光 → 换下一个号」，不是章数。
     * 带上 limit 的话，代券多的号会在还买得起的时候被条数挡住，剩下的章就得等下一趟 ——
     * 而用户要的是「一个账号代券足够就订阅到代券花光」。
     */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND chapter_no >= :fromNo "
            + "AND id NOT IN (SELECT chapter_id FROM purchase) "
            + "ORDER BY chapter_no ASC")
    List<Chapter> findUnownedChaptersFrom(long novelId, int fromNo);

    /** 起始章之后、还没人买的最小章，界面上的「下一章该买」用它。 */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND chapter_no >= :fromNo "
            + "AND id NOT IN (SELECT chapter_id FROM purchase) "
            + "ORDER BY chapter_no ASC LIMIT 1")
    Chapter findNextUnownedChapterFrom(long novelId, int fromNo);

    @Query("UPDATE novel SET start_chapter_no = :chapterNo WHERE id = :id")
    void setStartChapter(long id, int chapterNo);

    /**
     * 这一章的候选账号，按<b>代券</b>最多排序。队列会挨个试，被花费上限挡住就换下一个。
     *
     * <p>为什么不是火券：章节费两种券都能付，但菠萝包会先拿代券抵扣，而用户不充值火券 ——
     * 火券多的号在这里毫无意义，能不能买下一章完全看代券。按火券排序会让「有 2000 火券、
     * 0 代券」的号一直排在最前面，每轮都白走一趟。
     */
    @Query("SELECT * FROM account WHERE enabled = 1 AND id NOT IN "
            + "(SELECT account_id FROM purchase WHERE chapter_id = :chapterId) "
            + "ORDER BY last_known_vouchers DESC, sort_order ASC, id ASC")
    List<Account> suggestBuyers(long chapterId);

    /** 某账号在 since 之后花掉的火券。 */
    @Query("SELECT CAST(IFNULL(SUM(cost_coupons), 0) AS INTEGER) FROM purchase "
            + "WHERE account_id = :accountId AND purchased_at >= :since")
    int spentSince(long accountId, long since);

    /** 某账号在 since 之后花掉的代券，每日花费上限按这个算（花的本来就是代券）。 */
    @Query("SELECT CAST(IFNULL(SUM(cost_vouchers), 0) AS INTEGER) FROM purchase "
            + "WHERE account_id = :accountId AND purchased_at >= :since")
    int spentVouchersSince(long accountId, long since);

    /** 这一章该让哪个号买：还没买过、已启用、<b>代券</b>最多的那个。 */
    @Query("SELECT * FROM account WHERE enabled = 1 AND id NOT IN "
            + "(SELECT account_id FROM purchase WHERE chapter_id = :chapterId) "
            + "ORDER BY last_known_vouchers DESC, sort_order ASC, id ASC LIMIT 1")
    Account suggestBuyer(long chapterId);

    /**
     * 每个号的累计。
     *
     * <p>{@code chapterCount} <b>不数</b> {@code source='OWNED'} 的记录：那是照界面上的
     * 「已下载」回填的，而「已下载」是本机状态、8 个号共用（见 {@link Purchase#SRC_OWNED}）。
     * 把它数进「这个号买了多少章」，8 个号就会各自声称拥有同一批章 —— 界面上会出现
     * 「8 个号共 380 章」这种比全书章数还多的数，而真花过钱的只有 4 章。
     * 本机能看的那些单独放在 {@code deviceCount} 里，界面分开说。
     */
    @Query("SELECT a.id AS accountId, a.label AS label, a.nickname AS nickname, "
            + "a.login_name AS loginName, a.last_known_coupons AS coupons, "
            + "a.last_known_vouchers AS vouchers, "
            + "CAST(IFNULL(SUM(CASE WHEN p.id IS NOT NULL "
            + "AND IFNULL(p.source, '') <> 'OWNED' THEN 1 ELSE 0 END), 0) AS INTEGER) "
            + "AS chapterCount, "
            + "CAST(IFNULL(SUM(CASE WHEN IFNULL(p.source, '') = 'OWNED' THEN 1 ELSE 0 END), 0) "
            + "AS INTEGER) AS deviceCount, "
            + "CAST(IFNULL(SUM(p.cost_coupons), 0) AS INTEGER) AS totalCost, "
            + "CAST(IFNULL(SUM(p.cost_vouchers), 0) AS INTEGER) AS totalVouchers "
            + "FROM account a LEFT JOIN purchase p ON p.account_id = a.id "
            + "GROUP BY a.id ORDER BY a.sort_order ASC, a.id ASC")
    LiveData<List<AccountStat>> observeAccountStats();
}
