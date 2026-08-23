package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteConstraintException;

import org.junit.Test;

import java.util.List;

/** 账号表与签到日志的约束。日志必须每号每天一条，否则重跑会把界面刷成一堆重复。 */
public class AccountAndCheckInDaoTest extends DbTestBase {

    private static CheckInLog log(long accountId, String ymd, String status, long at) {
        CheckInLog l = new CheckInLog();
        l.accountId = accountId;
        l.dateYmd = ymd;
        l.status = status;
        l.createdAt = at;
        return l;
    }

    @Test
    public void loginNameIsUnique() {
        newAccount("a@x.com", 0, true, 1);
        try {
            newAccount("a@x.com", 0, true, 2);
            fail("同一个登录名被存了两次，切号逻辑会混乱");
        } catch (SQLiteConstraintException expected) {
            // 正是想要的
        }
        assertEquals(1, accounts.loadAll().size());
    }

    @Test
    public void lookupsUsedByAutomationAndCsvImport() {
        long id = newAccount("a@x.com", 30, true, 1);
        accounts.setNickname(id, "小明");
        accounts.setCoupons(id, 55);
        accounts.setLastCheckInAt(id, 1234L);

        Account a = accounts.byId(id);
        assertEquals("小明", a.nickname);
        assertEquals(55, a.lastKnownCoupons);
        assertEquals(1234L, a.lastCheckInAt);
        assertEquals(id, accounts.byLoginName("a@x.com").id);
        assertEquals(id, accounts.byNickname("小明").id);
        assertNull(accounts.byLoginName("nobody@x.com"));
        assertEquals(1, accounts.maxSortOrder());
    }

    @Test
    public void onlyEnabledAccountsAreQueued() {
        newAccount("on@x.com", 0, true, 1);
        newAccount("off@x.com", 0, false, 2);
        assertEquals(2, accounts.loadAll().size());
        List<Account> enabled = accounts.loadEnabled();
        assertEquals(1, enabled.size());
        assertEquals("on@x.com", enabled.get(0).loginName);
    }

    @Test
    public void checkInLogIsOnePerAccountPerDay() {
        long acc = newAccount("a@x.com", 0, true, 1);

        checkIns.upsert(log(acc, "2026-08-22", CheckInLog.FAILED, 100));
        checkIns.upsert(log(acc, "2026-08-22", CheckInLog.OK, 200));
        checkIns.upsert(log(acc, "2026-08-23", CheckInLog.OK, 300));

        CheckInLog today = checkIns.find(acc, "2026-08-22");
        assertNotNull(today);
        assertEquals("重跑应当覆盖当天那条", CheckInLog.OK, today.status);
        assertTrue(today.isSuccess());
        assertEquals(2, checkIns.loadRecent(10).size());
        assertEquals(300L, checkIns.loadRecent(1).get(0).createdAt);
    }

    @Test
    public void deletingAnAccountTakesItsLogsWithIt() {
        long acc = newAccount("a@x.com", 0, true, 1);
        checkIns.upsert(log(acc, "2026-08-22", CheckInLog.OK, 100));

        accounts.delete(accounts.byId(acc));

        assertNull(checkIns.find(acc, "2026-08-22"));
        assertTrue(checkIns.loadRecent(10).isEmpty());
    }

    @Test
    public void purgeOlderThanKeepsRecentLogs() {
        long acc = newAccount("a@x.com", 0, true, 1);
        checkIns.upsert(log(acc, "2026-08-01", CheckInLog.OK, 100));
        checkIns.upsert(log(acc, "2026-08-22", CheckInLog.OK, 900));

        assertEquals(1, checkIns.purgeOlderThan(500));
        List<CheckInLog> left = checkIns.loadRecent(10);
        assertEquals(1, left.size());
        assertEquals("2026-08-22", left.get(0).dateYmd);
    }
}
