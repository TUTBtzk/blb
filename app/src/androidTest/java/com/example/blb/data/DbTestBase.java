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

    protected long newAccount(String login, int coupons, boolean enabled, int sortOrder) {
        Account a = new Account();
        a.loginName = login;
        a.label = login;
        a.lastKnownCoupons = coupons;
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
}
