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
    void deleteNovel(Novel novel);

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
    void deleteChapter(Chapter chapter);

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsertPurchase(Purchase purchase);

    @Delete
    void deletePurchase(Purchase purchase);

    @Query("SELECT * FROM purchase WHERE chapter_id IN "
            + "(SELECT id FROM chapter WHERE novel_id = :novelId)")
    List<Purchase> loadPurchasesOfNovel(long novelId);

    @Query("SELECT * FROM purchase WHERE chapter_id IN "
            + "(SELECT id FROM chapter WHERE novel_id = :novelId)")
    LiveData<List<Purchase>> observePurchasesOfNovel(long novelId);

    @Query("SELECT COUNT(*) FROM purchase WHERE account_id = :accountId AND chapter_id = :chapterId "
            + "AND IFNULL(source, '') != 'DRY_RUN'")
    int countRealPurchase(long accountId, long chapterId);

    @Query("SELECT p.id AS purchaseId, p.account_id AS accountId, p.chapter_id AS chapterId, "
            + "p.cost_coupons AS costCoupons, p.purchased_at AS purchasedAt, p.source AS source, "
            + "c.chapter_no AS chapterNo, c.title AS chapterTitle, n.title AS novelTitle, "
            + "a.label AS accountLabel, a.nickname AS accountNickname, a.login_name AS accountLoginName "
            + "FROM purchase p "
            + "JOIN chapter c ON c.id = p.chapter_id "
            + "JOIN novel n ON n.id = c.novel_id "
            + "JOIN account a ON a.id = p.account_id "
            + "ORDER BY p.purchased_at DESC LIMIT :limit")
    LiveData<List<PurchaseRow>> observeRecentRows(int limit);

    @Query("SELECT p.id AS purchaseId, p.account_id AS accountId, p.chapter_id AS chapterId, "
            + "p.cost_coupons AS costCoupons, p.purchased_at AS purchasedAt, p.source AS source, "
            + "c.chapter_no AS chapterNo, c.title AS chapterTitle, n.title AS novelTitle, "
            + "a.label AS accountLabel, a.nickname AS accountNickname, a.login_name AS accountLoginName "
            + "FROM purchase p "
            + "JOIN chapter c ON c.id = p.chapter_id "
            + "JOIN novel n ON n.id = c.novel_id "
            + "JOIN account a ON a.id = p.account_id "
            + "ORDER BY n.title ASC, c.chapter_no ASC, a.id ASC")
    List<PurchaseRow> loadAllRows();

    // ---------- 建议 ----------

    /** 所有账号都还没真买过的最小章节。 */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND id NOT IN "
            + "(SELECT chapter_id FROM purchase WHERE IFNULL(source, '') != 'DRY_RUN') "
            + "ORDER BY chapter_no ASC LIMIT 1")
    Chapter findNextUnownedChapter(long novelId);

    /**
     * 自动订阅一轮要处理的章节。跟 {@link #findNextUnownedChapter} 同一套条件，
     * 只是一次取多条 —— 干跑不写真购买记录，靠「取一条」推进会在同一章上打转。
     */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND id NOT IN "
            + "(SELECT chapter_id FROM purchase WHERE IFNULL(source, '') != 'DRY_RUN') "
            + "ORDER BY chapter_no ASC LIMIT :limit")
    List<Chapter> findUnownedChapters(long novelId, int limit);

    /**
     * 同上，但只看「起始章」之后的章。你指定从第 N 章开始订阅时，前面那些旧章
     * 就算没人买也不该动 —— 券花在那儿不会让你更早看到新章。
     */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND chapter_no >= :fromNo "
            + "AND id NOT IN "
            + "(SELECT chapter_id FROM purchase WHERE IFNULL(source, '') != 'DRY_RUN') "
            + "ORDER BY chapter_no ASC LIMIT :limit")
    List<Chapter> findUnownedChaptersFrom(long novelId, int fromNo, int limit);

    /** 起始章之后、还没人真买的最小章，界面上的「下一章该买」用它。 */
    @Query("SELECT * FROM chapter WHERE novel_id = :novelId AND chapter_no >= :fromNo "
            + "AND id NOT IN "
            + "(SELECT chapter_id FROM purchase WHERE IFNULL(source, '') != 'DRY_RUN') "
            + "ORDER BY chapter_no ASC LIMIT 1")
    Chapter findNextUnownedChapterFrom(long novelId, int fromNo);

    @Query("UPDATE novel SET start_chapter_no = :chapterNo WHERE id = :id")
    void setStartChapter(long id, int chapterNo);

    /** 这一章的候选账号，按「火券最多」排序；队列会挨个试，被花费上限挡住就换下一个。 */
    @Query("SELECT * FROM account WHERE enabled = 1 AND id NOT IN "
            + "(SELECT account_id FROM purchase WHERE chapter_id = :chapterId "
            + " AND IFNULL(source, '') != 'DRY_RUN') "
            + "ORDER BY last_known_coupons DESC, sort_order ASC, id ASC")
    List<Account> suggestBuyers(long chapterId);

    /** 某账号在 since 之后真实花掉的火券，用于每日花费上限。 */
    @Query("SELECT CAST(IFNULL(SUM(cost_coupons), 0) AS INTEGER) FROM purchase "
            + "WHERE account_id = :accountId AND purchased_at >= :since "
            + "AND IFNULL(source, '') != 'DRY_RUN'")
    int spentSince(long accountId, long since);

    /** 这一章该让哪个号买：还没买过、已启用、火券最多的那个。 */
    @Query("SELECT * FROM account WHERE enabled = 1 AND id NOT IN "
            + "(SELECT account_id FROM purchase WHERE chapter_id = :chapterId "
            + " AND IFNULL(source, '') != 'DRY_RUN') "
            + "ORDER BY last_known_coupons DESC, sort_order ASC, id ASC LIMIT 1")
    Account suggestBuyer(long chapterId);

    @Query("SELECT a.id AS accountId, a.label AS label, a.nickname AS nickname, "
            + "a.login_name AS loginName, a.last_known_coupons AS coupons, "
            + "COUNT(p.id) AS chapterCount, CAST(IFNULL(SUM(p.cost_coupons), 0) AS INTEGER) AS totalCost "
            + "FROM account a LEFT JOIN purchase p "
            + "  ON p.account_id = a.id AND IFNULL(p.source, '') != 'DRY_RUN' "
            + "GROUP BY a.id ORDER BY a.sort_order ASC, a.id ASC")
    LiveData<List<AccountStat>> observeAccountStats();
}
