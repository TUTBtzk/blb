package com.example.blb.data;

/** 每个账号在账本里的汇总。 */
public class AccountStat {
    public long accountId;
    public String label;
    public String nickname;
    public String loginName;
    public int chapterCount;
    public int totalCost;
    public int coupons;

    public String displayName() {
        if (label != null && !label.trim().isEmpty()) return label;
        if (nickname != null && !nickname.trim().isEmpty()) return nickname;
        return loginName == null ? "" : loginName;
    }
}
