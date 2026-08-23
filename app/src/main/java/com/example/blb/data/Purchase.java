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
    /** 干跑：走完流程但没真的点确认，只留痕。 */
    public static final String SRC_DRY_RUN = "DRY_RUN";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "account_id")
    public long accountId;

    @ColumnInfo(name = "chapter_id")
    public long chapterId;

    @ColumnInfo(name = "cost_coupons")
    public int costCoupons;

    @ColumnInfo(name = "purchased_at")
    public long purchasedAt;

    public String source;

    public static Purchase of(long accountId, long chapterId, int cost, String source) {
        Purchase p = new Purchase();
        p.accountId = accountId;
        p.chapterId = chapterId;
        p.costCoupons = cost;
        p.source = source;
        p.purchasedAt = System.currentTimeMillis();
        return p;
    }

    /** 干跑记录不代表真的拥有这一章。 */
    public boolean isReal() {
        return !SRC_DRY_RUN.equals(source);
    }
}
