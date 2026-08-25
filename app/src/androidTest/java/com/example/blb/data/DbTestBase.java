package com.example.blb.data;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;

/** 各 DAO 测试的公共脚手架：内存库 + 建测试数据的小工具。 */
@RunWith(AndroidJUnit4.class)
public abstract class DbTestBase {

    protected AppDatabase db;
    protected AccountDao accounts;
    protected SubscriptionDao subs;
    protected CheckInDao checkIns;

    @Before
    public void openDb() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        accounts = db.accountDao();
        subs = db.subscriptionDao();
        checkIns = db.checkInDao();
    }

    @After
    public void closeDb() {
        db.close();
    }

    /** 只记火券的账号；代券留在 -1（没读到过）。挑号顺序按代券排，所以这些号在排序上是并列的。 */
    protected long newAccount(String login, int coupons, boolean enabled, int sortOrder) {
        return newAccount(login, coupons, -1, enabled, sortOrder);
    }

    /**
     * 两种券都记的账号。挑号只看代券（{@code last_known_vouchers}）—— 章节费两种券都能付，
     * 但菠萝包先扣代券，而用户不充值火券，所以火券多少不影响能不能买下一章。
     */
    protected long newAccount(String login, int coupons, int vouchers, boolean enabled,
                              int sortOrder) {
        Account a = new Account();
        a.loginName = login;
        a.label = login;
        a.lastKnownCoupons = coupons;
        a.lastKnownVouchers = vouchers;
        a.enabled = enabled;
        a.sortOrder = sortOrder;
        return accounts.insert(a);
    }

    protected long newNovel(String title, boolean target) {
        Novel n = new Novel();
        n.title = title;
        n.isTarget = target;
        return subs.insertNovel(n);
    }

    protected long newChapter(long novelId, int no, int price) {
        Chapter c = new Chapter();
        c.novelId = novelId;
        c.chapterNo = no;
        c.title = "第" + no + "章";
        c.priceCoupons = price;
        return subs.insertChapter(c);
    }

    protected long buy(long accountId, long chapterId, int cost, String source) {
        return subs.upsertPurchase(Purchase.of(accountId, chapterId, cost, source));
    }

    /** 两种券分别记的一笔购买。每日花费上限按代券算，所以代券那一列要能单独造。 */
    protected long buy(long accountId, long chapterId, int cost, int costVouchers, String source) {
        return subs.upsertPurchase(Purchase.of(accountId, chapterId, cost, costVouchers, source));
    }
}
