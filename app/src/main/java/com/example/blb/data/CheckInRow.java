package com.example.blb.data;

import androidx.room.ColumnInfo;

/**
 * 「今日各账号签到状态」一行。用 LEFT JOIN 拼出来，所以没跑过的账号也会出现（status 为 null）。
 */
public class CheckInRow {

    @ColumnInfo(name = "account_id")
    public long accountId;

    @ColumnInfo(name = "account_label")
    public String label;

    @ColumnInfo(name = "account_nickname")
    public String nickname;

    @ColumnInfo(name = "account_login")
    public String login;

    /** null 表示今天还没跑过这个账号。 */
    public String status;

    public String message;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public String accountName() {
        if (label != null && !label.trim().isEmpty()) return label;
        if (nickname != null && !nickname.trim().isEmpty()) return nickname;
        return login;
    }

    /** 中文状态，界面直接用。 */
    public String statusText() {
        if (status == null) return "今天还没跑";
        switch (status) {
            case CheckInLog.OK:
                return "签到成功";
            case CheckInLog.ALREADY:
                return "已签到";
            case CheckInLog.FAILED:
                return "失败";
            case CheckInLog.BLOCKED_CAPTCHA:
                return "被验证码挡住";
            case CheckInLog.SKIPPED:
                return "已跳过";
            default:
                return status;
        }
    }

}
