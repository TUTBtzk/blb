package com.example.blb.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Account;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 账号页的两句话：顶部那一行总览，和每一行那句状态。
 *
 * <p>为什么值得断死：余额是「还要不要充值」的依据，而「缺密码」是切号一定会失败的前置条件 ——
 * 这两件事算错或漏说，使用者都会在跑队列时才发现。两句都写在 {@link AccountOverviewText} /
 * {@link AccountRowText} 里（不碰 Android API），这里逐条断。
 */
public class AccountsPageTextTest {

    /** 账号密码登录方式、而且存了密码 —— 也就是「齐了，能自己跑」的那个默认样子。 */
    private static Account account(String name, boolean enabled) {
        Account a = new Account();
        a.label = name;
        a.loginName = name;
        a.enabled = enabled;
        a.encPassword = new byte[]{1};
        a.encIv = new byte[]{2};
        return a;
    }

    // ---------- 顶部那一行总览 ----------

    @Test
    public void overviewCountsEnabledDisabledAndBalance() {
        Account a = account("主号", true);
        a.lastKnownVouchers = 300;
        Account b = account("备用1", true);
        b.lastKnownVouchers = 40;
        Account c = account("备用2", false);
        c.lastKnownVouchers = 20;
        String line = AccountOverviewText.line(Arrays.asList(a, b, c));
        assertTrue(line, line.contains("2 个启用"));
        assertTrue(line, line.contains("1 个停用"));
        assertTrue(line, line.contains("代券合计 360"));
        assertEquals(StatusPalette.OK, AccountOverviewText.tone(Arrays.asList(a, b, c)));
    }

    @Test
    public void unreadBalanceIsNotAddedAsZero() {
        // -1＝这个号的余额一次都没读到。当 0 加进去会让「合计」看着比实际少。
        Account a = account("主号", true);
        a.lastKnownVouchers = 15;
        Account b = account("备用1", true);
        b.lastKnownVouchers = -1;
        String line = AccountOverviewText.line(Arrays.asList(a, b));
        assertTrue(line, line.contains("代券合计 15"));
        assertTrue(line, line.contains("1 个号还没读到余额"));
    }

    @Test
    public void missingPasswordIsFlaggedAndTurnsTheLineAmber() {
        // 密码登录却没存密码＝切号一定失败，必须在跑之前就在这一行看见。
        Account a = account("主号", true);
        a.encPassword = null;
        a.encIv = null;
        String line = AccountOverviewText.line(Collections.singletonList(a));
        assertTrue(line, line.contains("有号缺密码"));
        assertEquals(StatusPalette.WARN, AccountOverviewText.tone(Collections.singletonList(a)));
    }

    @Test
    public void noEnabledAccountIsGreyAndSaysNothingAboutFire() {
        Account a = account("备用1", false);
        a.lastKnownCoupons = 0;
        String line = AccountOverviewText.line(Collections.singletonList(a));
        assertTrue(line, line.contains("0 个启用"));
        // 火券为 0 或没读到就整段不提，别占掉代券那一位。
        assertFalse(line, line.contains("火券"));
        assertEquals(StatusPalette.SKIP, AccountOverviewText.tone(Collections.singletonList(a)));
    }

    @Test
    public void emptyAccountListSaysSoInsteadOfZeroes() {
        assertEquals("还没有账号", AccountOverviewText.line(new ArrayList<Account>()));
        assertEquals(StatusPalette.SKIP, AccountOverviewText.tone(null));
    }

    // ---------- 每一行那句状态 ----------

    @Test
    public void aWorkingAccountSaysItCanLogInWithBalanceAndLastCheckIn() {
        Account a = account("主号", true);
        a.lastKnownVouchers = 340;
        String line = AccountRowText.statusLine(a, "09-14 21:30");
        assertTrue(line, line.startsWith("可以自动登录"));
        assertTrue(line, line.contains("代券 340"));
        assertTrue(line, line.contains("上次签到 09-14 21:30"));
    }

    @Test
    public void disabledAccountSaysItWillNotRun() {
        Account a = account("备用2", false);
        String line = AccountRowText.statusLine(a, "");
        assertTrue(line, line.startsWith("已停用"));
        assertTrue(line, line.contains("还没签过"));
    }

    @Test
    public void passwordAccountWithoutPasswordWarnsInsteadOfClaimingItCanRun() {
        Account a = account("主号", true);
        a.encPassword = null;
        a.encIv = null;
        assertTrue(AccountRowText.statusLine(a, "").startsWith("缺密码，切号会失败"));
    }

    @Test
    public void unreadBalanceShowsAQuestionMarkNotZero() {
        Account a = account("主号", true);
        a.lastKnownVouchers = -1;
        assertTrue(AccountRowText.statusLine(a, "").contains("代券 ?"));
    }

    @Test
    public void nicknameOnlyShowsUpWhenItDiffersFromTheDisplayName() {
        // 昵称是「切号之后到底登对没有」的核对依据；跟标题一样就是废话。
        Account a = account("主号", true);
        a.nickname = "星野";
        assertTrue(AccountRowText.statusLine(a, "").contains("菠萝包昵称 星野"));

        Account same = account("主号", true);
        same.nickname = "主号";
        assertFalse(AccountRowText.statusLine(same, "").contains("菠萝包昵称"));
    }

    @Test
    public void fireCouponsOnlyShowUpWhenThereReallyAreSome() {
        Account none = account("主号", true);
        assertFalse(AccountRowText.statusLine(none, "").contains("火券"));
        Account legacy = account("主号", true);
        legacy.lastKnownCoupons = 25;
        assertTrue(AccountRowText.statusLine(legacy, "").contains("火券 25"));
    }

    @Test
    public void everyStatusSentenceIsOneLine() {
        // 「一句状态」是这一行的全部：换行会把卡片撑成三行，扫不过来。
        List<Account> all = Arrays.asList(account("主号", true), account("备用1", false));
        for (Account a : all) {
            assertFalse(AccountRowText.statusLine(a, "09-14 21:30").contains("\n"));
        }
    }
}
