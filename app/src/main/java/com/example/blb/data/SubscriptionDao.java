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

    @Query("UPDATE chapter SET chapter_no = :chapterNo WHERE id = :id")
    void setChapterNo(long id, int chapterNo);

    @Query("DELETE FROM chapter WHERE id = :id")
    void deleteChapterById(long id);

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
     * <p>为什么要连别的号的一起取：明细页只说「这个号买过第 N 章」，而「一章只许一个号」是全队的
     * 约束 —— 只有同时看得见别的号的记录，才认得出「菠萝包说这一章是甲买的、账本却记在乙名下」
     * 这种归属错乱（2026-08-25 第49章那件事）。
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
