package com.example.blb.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Purchase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「这一章是谁买的」——章节行点一下就照这个答案退登当前账号、去登那个号。
 *
 * <p>为什么值得断死：切号是退登＋重登，是这个 App 里最招验证码的动作，而使用者手指动不了，
 * 撞上验证码就得等人。答错一次不是显示错了一行字，是白挨一次登录。
 * 尤其 {@code source='OWNED'}（免费章，一章 8 条、8 个号名下各一条）没有买家 ——
 * 把它当买家等于随便挑个号切过去。
 */
public class ChapterOwnershipTest {

    private static Purchase bought(long accountId, long chapterId) {
        return Purchase.of(accountId, chapterId, 0, 15, Purchase.SRC_AUTO);
    }

    private static Purchase deviceOnly(long accountId, long chapterId) {
        return Purchase.of(accountId, chapterId, 0, 0, Purchase.SRC_OWNED);
    }

    @Test
    public void theBuyerIsTheAccountThatActuallyPaid() {
        ChapterOwnership own = ChapterOwnership.of(Arrays.asList(bought(7, 48)), 48);
        assertTrue(own.hasBuyer());
        assertEquals(Arrays.asList(7L), own.buyerIds);
        assertEquals(0, own.deviceOnly);
    }

    @Test
    public void deviceOnlyRecordsAreNeverTreatedAsBuyers() {
        // 那 47 章全是这种记录：8 个号名下各一条，一个号都没花过钱。
        List<Purchase> purchases = new ArrayList<>();
        for (long a = 1; a <= 8; a++) purchases.add(deviceOnly(a, 12));
        ChapterOwnership own = ChapterOwnership.of(purchases, 12);
        assertFalse(own.hasBuyer());
        assertTrue(own.buyerIds.isEmpty());
        assertEquals(8, own.deviceOnly);
    }

    @Test
    public void aRealBuyerStillWinsWhenTheDeviceAlsoShowsItOwned() {
        ChapterOwnership own = ChapterOwnership.of(
                Arrays.asList(deviceOnly(3, 49), bought(5, 49), deviceOnly(6, 49)), 49);
        assertEquals(Arrays.asList(5L), own.buyerIds);
        assertEquals(2, own.deviceOnly);
    }

    @Test
    public void purchasesOfOtherChaptersAreIgnored() {
        ChapterOwnership own = ChapterOwnership.of(
                Arrays.asList(bought(2, 47), bought(4, 48), bought(6, 49)), 48);
        assertEquals(Arrays.asList(4L), own.buyerIds);
    }

    @Test
    public void manualRecordsCountAsBuyersToo() {
        // 手工补录（照订阅清单填的）就是「这个号买过」，点一下该切到它。
        ChapterOwnership own = ChapterOwnership.of(
                Arrays.asList(Purchase.of(9, 50, 0, 15, Purchase.SRC_MANUAL)), 50);
        assertEquals(Arrays.asList(9L), own.buyerIds);
    }

    @Test
    public void twoAccountsOnOneChapterAreBothReportedSoTheUiCanAsk() {
        // 一章两个号买过是要修的错（钱白花了），但切号不许替他猜 —— 界面得拿到两个人去问。
        ChapterOwnership own = ChapterOwnership.of(
                Arrays.asList(bought(3, 60), bought(8, 60)), 60);
        assertEquals(Arrays.asList(3L, 8L), own.buyerIds);
    }

    @Test
    public void theSameAccountTwiceIsStillOneBuyer() {
        ChapterOwnership own = ChapterOwnership.of(
                Arrays.asList(bought(3, 61), bought(3, 61)), 61);
        assertEquals(Arrays.asList(3L), own.buyerIds);
    }

    @Test
    public void noRecordsMeansNoBuyerInsteadOfBlowingUp() {
        assertFalse(ChapterOwnership.of(null, 1).hasBuyer());
        assertFalse(ChapterOwnership.of(new ArrayList<>(), 1).hasBuyer());
    }
}
