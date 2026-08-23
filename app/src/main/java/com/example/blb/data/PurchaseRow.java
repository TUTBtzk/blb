package com.example.blb.data;

/** 「哪个账号订阅了哪本小说的哪一章」的一行可读记录，给列表和 CSV 用。 */
public class PurchaseRow {
    public long purchaseId;
    public long accountId;
    public long chapterId;
    public int costCoupons;
    public long purchasedAt;
    public String source;
    public int chapterNo;
    public String chapterTitle;
    public String novelTitle;
    public String accountLabel;
    public String accountNickname;
    public String accountLoginName;

    public String accountDisplayName() {
        if (accountLabel != null && !accountLabel.trim().isEmpty()) return accountLabel;
        if (accountNickname != null && !accountNickname.trim().isEmpty()) return accountNickname;
        return accountLoginName == null ? "" : accountLoginName;
    }
}
