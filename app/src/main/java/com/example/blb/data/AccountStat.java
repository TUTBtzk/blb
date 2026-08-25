package com.example.blb.data;

/** 每个账号在账本里的汇总。 */
public class AccountStat {
    public long accountId;
    public String label;
    public String nickname;
    public String loginName;
    public int chapterCount;
    /** 累计实付火券。新流程只买不动火券的章，所以它只会是历史遗留。 */
    public int totalCost;
    /** 累计实付代券 —— 现在真正花掉的就是这一项。 */
    public int totalVouchers;
    public int coupons;
    /** 最近读到的代券余额，-1 = 从没读到过。 */
    public int vouchers = -1;

    public String displayName() {
        if (label != null && !label.trim().isEmpty()) return label;
        if (nickname != null && !nickname.trim().isEmpty()) return nickname;
        return loginName == null ? "" : loginName;
    }
}
