package com.example.blb.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 「哪个账号订阅了哪一章」的一条记录。(accountId, chapterId) 唯一，
 * 所以自动化重跑或手动补录都不会产生重复账。
 */
@Entity(tableName = "purchase",
        foreignKeys = {
                @ForeignKey(entity = Account.class, parentColumns = "id",
                        childColumns = "account_id", onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = Chapter.class, parentColumns = "id",
                        childColumns = "chapter_id", onDelete = ForeignKey.CASCADE)},
        indices = {
                @Index(value = {"account_id", "chapter_id"}, unique = true),
                @Index("chapter_id")})
public class Purchase {

    /** 自动化实际点了订阅。 */
    public static final String SRC_AUTO = "AUTO";
    /** 你自己在 App 里手动补录的。 */
    public static final String SRC_MANUAL = "MANUAL";
    /**
     * 界面证明这个号已经能看这一章（选择章节页那一行写着<b>「已下载」</b>，或者根本没有锁＝免费章），
     * 但花了多少券无从得知。
     *
     * <p>为什么不看锁：付费章买完之后锁<b>还在</b>，只是红色实心闭锁变成红边白底开锁，
     * 而这两种图标在无障碍树里是同一个空文本节点 —— 2026-08-24 就是按锁判的，
     * 券扣了 40 代券却一条账都没记。判据见 {@code ChapterRowState}。
     *
     * <p>这是「8 个号合起来拼出完整一本」唯一不用花钱就能拿到的账面依据：以前必须先手工
     * 登记章节、再手工登记谁买过，612 章根本登记不完，于是自动订阅永远在打「没有待订阅的
     * 章节」。现在改成每次进选择章节页时按「已下载」回填。花费一律记 0 —— 编一个价钱会污染
     * 「今天花了多少券」这本账；真实花费只有当场买的那一趟（{@link #SRC_AUTO}）
     * 或按订阅清单补录（debug 的 {@code RECORD_BOUGHT}）才知道。
     */
    public static final String SRC_OWNED = "OWNED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "account_id")
    public long accountId;

    @ColumnInfo(name = "chapter_id")
    public long chapterId;

    @ColumnInfo(name = "cost_coupons")
    public int costCoupons;

    /**
     * 实付代券。新流程只买「实付火券 == 0」的章，所以真正花掉的都记在这里。
     * v4 之前的旧记录一律是 0（当年按火券记的账，代券花费无从得知）。
     */
    @ColumnInfo(name = "cost_vouchers")
    public int costVouchers;

    @ColumnInfo(name = "purchased_at")
    public long purchasedAt;

    public String source;

    public static Purchase of(long accountId, long chapterId, int cost, String source) {
        return of(accountId, chapterId, cost, 0, source);
    }

    public static Purchase of(long accountId, long chapterId, int cost, int costVouchers,
                              String source) {
        Purchase p = new Purchase();
        p.accountId = accountId;
        p.chapterId = chapterId;
        p.costCoupons = cost;
        p.costVouchers = costVouchers;
        p.source = source;
        p.purchasedAt = System.currentTimeMillis();
        return p;
    }
}
