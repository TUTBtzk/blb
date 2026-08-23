package com.example.blb.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 一个账号某一天的签到结果。(accountId, dateYmd) 唯一，重跑幂等。 */
@Entity(tableName = "check_in_log",
        foreignKeys = @ForeignKey(entity = Account.class,
                parentColumns = "id",
                childColumns = "account_id",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index(value = {"account_id", "date_ymd"}, unique = true)})
public class CheckInLog {

    /** 本次点成了。 */
    public static final String OK = "OK";
    /** 打开时已经是已签到状态。 */
    public static final String ALREADY = "ALREADY";
    /** 步骤超时/找不到控件等。 */
    public static final String FAILED = "FAILED";
    /** 撞上验证码或安全验证，已停下等人工。 */
    public static final String BLOCKED_CAPTCHA = "BLOCKED_CAPTCHA";
    /** 你手动跳过了这个账号。 */
    public static final String SKIPPED = "SKIPPED";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "account_id")
    public long accountId;

    /** yyyy-MM-dd，本地时区。 */
    @ColumnInfo(name = "date_ymd")
    public String dateYmd;

    public String status;

    /** 签到页有没有「看广告领奖励」入口待你手动领。 */
    @ColumnInfo(name = "ad_available")
    public boolean adAvailable;

    /** 今天这个号已经看完并领取了几个广告奖励（由你在暂停时确认，脚本不自动播放）。 */
    @ColumnInfo(name = "ads_watched")
    public int adsWatched;

    /** 界面上读到的「今日还剩几次」。-1 表示读不到。 */
    @ColumnInfo(name = "ads_remaining")
    public int adsRemaining = -1;

    public String message;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public boolean isSuccess() {
        return OK.equals(status) || ALREADY.equals(status);
    }
}
