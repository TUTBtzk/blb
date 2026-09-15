package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.util.Csv;
import com.example.blb.util.PurchaseCsv;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class LedgerWritePolicyTest {
    private static Purchase purchase(long id, long account, int coupons, int vouchers, String source) {
        Purchase row = new Purchase();
        row.id = id;
        row.accountId = account;
        row.chapterId = 50;
        row.costCoupons = coupons;
        row.costVouchers = vouchers;
        row.purchasedAt = 1_700_000_000_000L;
        row.source = source;
        return row;
    }

    @Test
    public void aNewKnownFactCanBeInserted() {
        Purchase incoming = purchase(0, 1, 0, 20, Purchase.SRC_AUTO);
        assertEquals(LedgerWritePolicy.Action.INSERT,
                LedgerWritePolicy.decide(incoming, Collections.emptyList()).action);
    }

    @Test
    public void fourSourcesOnlyAllowRemoteHistoryToAddAnotherPaidAccount() {
        for (String source : new String[]{Purchase.SRC_AUTO, Purchase.SRC_MANUAL,
                Purchase.SRC_REMOTE_DETAIL, Purchase.SRC_OWNED}) {
            Purchase incoming = purchase(0, 1, 0,
                    Purchase.SRC_OWNED.equals(source) ? 0 : 20, source);
            assertEquals(source, LedgerWritePolicy.Action.INSERT,
                    LedgerWritePolicy.decide(incoming, Collections.emptyList()).action);
            Purchase other = purchase(101, 2, 0, 20, Purchase.SRC_REMOTE_DETAIL);
            LedgerWritePolicy.Decision duplicate = LedgerWritePolicy.decide(incoming,
                    Collections.singletonList(other));
            boolean allowed = Purchase.SRC_REMOTE_DETAIL.equals(source) || Purchase.SRC_OWNED.equals(source);
            assertEquals(source, allowed ? LedgerWritePolicy.Action.INSERT : LedgerWritePolicy.Action.REJECT,
                    duplicate.action);
            if (!allowed) assertEquals("这一章已有其他账号花过券；如果服务器上确实两个号都订过，请用『核对订阅清单』按明细补录",
                    duplicate.message);
        }
    }

    @Test
    public void aThirdRemoteAccountCanBeRecordedWithoutChangingExistingFacts() {
        Purchase first = purchase(101, 2, 0, 20, Purchase.SRC_AUTO);
        Purchase second = purchase(102, 3, 0, 22, Purchase.SRC_REMOTE_DETAIL);
        Purchase incoming = purchase(0, 1, 0, 18, Purchase.SRC_REMOTE_DETAIL);
        assertEquals(LedgerWritePolicy.Action.INSERT,
                LedgerWritePolicy.decide(incoming, Arrays.asList(first, second)).action);
        assertEquals(20, first.costVouchers);
        assertEquals(22, second.costVouchers);
        Purchase stored = purchase(103, 1, 0, 18, Purchase.SRC_REMOTE_DETAIL);
        LedgerWritePolicy.Decision replay = LedgerWritePolicy.decide(incoming,
                Arrays.asList(first, second, stored));
        assertEquals(LedgerWritePolicy.Action.KEEP, replay.action);
        assertEquals(103L, replay.existingId);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decide(incoming,
                Arrays.asList(first, stored, stored)).action);
    }

    @Test
    public void csvCannotClaimVerifiedRemoteEvidenceBySupplyingItsSourceField() {
        Purchase incoming = PurchaseCsv.parsePurchase(Csv.parse(
                "测试书,50,第50章 测试章,b@test,0,REMOTE_DETAIL,1700000000000,20").get(0));
        assertNotNull(incoming);
        incoming.accountId = 1;
        incoming.chapterId = 50;
        Purchase other = purchase(101, 2, 0, 20, Purchase.SRC_AUTO);

        LedgerWritePolicy.Decision imported = LedgerWritePolicy.decideImported(incoming,
                Collections.singletonList(other));
        assertEquals(LedgerWritePolicy.Action.REJECT, imported.action);
        assertEquals("这一章已有其他账号花过券；如果服务器上确实两个号都订过，请用『核对订阅清单』按明细补录",
                imported.message);
        assertEquals(Purchase.SRC_REMOTE_DETAIL, incoming.source);
        assertEquals(LedgerWritePolicy.Action.INSERT,
                LedgerWritePolicy.decide(incoming, Collections.singletonList(other)).action);
    }

    @Test
    public void importingAnIdenticalStoredRemoteFactKeepsItsIdBesideAnotherPaidAccount() {
        Purchase incoming = purchase(0, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL);
        Purchase stored = purchase(102, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL);
        Purchase other = purchase(101, 2, 0, 20, Purchase.SRC_AUTO);
        for (List<Purchase> rows : Arrays.asList(Arrays.asList(other, stored),
                Arrays.asList(stored, other))) {
            LedgerWritePolicy.Decision replay = LedgerWritePolicy.decideImported(incoming, rows);
            assertEquals(LedgerWritePolicy.Action.KEEP, replay.action);
            assertEquals(102L, replay.existingId);
        }
        assertEquals(Purchase.SRC_REMOTE_DETAIL, stored.source);
        assertEquals(20, stored.costVouchers);
        assertEquals(1_700_000_000_000L, stored.purchasedAt);
    }

    @Test
    public void importingWithoutAnotherPaidOwnerPreservesAllFourSources() {
        Purchase free = purchase(101, 2, 0, 0, Purchase.SRC_OWNED);
        for (String source : new String[]{Purchase.SRC_AUTO, Purchase.SRC_MANUAL,
                Purchase.SRC_REMOTE_DETAIL, Purchase.SRC_OWNED}) {
            Purchase incoming = purchase(0, 1, 0,
                    Purchase.SRC_OWNED.equals(source) ? 0 : 20, source);
            assertEquals(source, LedgerWritePolicy.Action.INSERT,
                    LedgerWritePolicy.decideImported(incoming, Collections.emptyList()).action);
            assertEquals(source, LedgerWritePolicy.Action.INSERT,
                    LedgerWritePolicy.decideImported(incoming, Collections.singletonList(free)).action);
            assertEquals(source, incoming.source);
        }
    }

    @Test
    public void importedFactsNeverOverwriteOrPromoteExistingFacts() {
        Purchase stored = purchase(102, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL);
        Purchase other = purchase(101, 2, 0, 20, Purchase.SRC_AUTO);
        List<Consumer<Purchase>> changes = Arrays.asList(row -> row.costCoupons++,
                row -> row.costVouchers++, row -> row.purchasedAt++,
                row -> row.source = Purchase.SRC_MANUAL, row -> row.id = 500);
        for (Consumer<Purchase> change : changes) {
            Purchase incoming = purchase(0, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL);
            change.accept(incoming);
            assertEquals(LedgerWritePolicy.Action.REJECT,
                    LedgerWritePolicy.decideImported(incoming, Arrays.asList(other, stored)).action);
        }
        Purchase owned = purchase(102, 1, 0, 0, Purchase.SRC_OWNED);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decideImported(
                purchase(0, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL),
                Arrays.asList(other, owned)).action);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decideImported(
                purchase(0, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL),
                Arrays.asList(stored, stored)).action);
    }

    @Test
    public void exactReplayKeepsTheOriginalPrimaryKey() {
        Purchase old = purchase(101, 1, 0, 20, Purchase.SRC_AUTO);
        for (long id : new long[]{0, 101}) {
            Purchase incoming = purchase(id, 1, 0, 20, Purchase.SRC_AUTO);
            LedgerWritePolicy.Decision result = LedgerWritePolicy.decide(incoming,
                    Collections.singletonList(old));
            assertEquals(LedgerWritePolicy.Action.KEEP, result.action);
            assertEquals(101L, result.existingId);
        }
    }

    @Test
    public void changedMoneyTimeSourceOrPrimaryKeyNeverReplacesAnOldFact() {
        Purchase old = purchase(101, 1, 0, 20, Purchase.SRC_AUTO);
        List<Consumer<Purchase>> edits = Arrays.asList(
                row -> row.costCoupons = 1, row -> row.costVouchers = 21,
                row -> row.purchasedAt++, row -> row.source = Purchase.SRC_MANUAL,
                row -> row.id = 102);
        for (Consumer<Purchase> edit : edits) {
            Purchase incoming = purchase(0, 1, 0, 20, Purchase.SRC_AUTO);
            edit.accept(incoming);
            assertEquals(LedgerWritePolicy.Action.REJECT,
                    LedgerWritePolicy.decide(incoming, Collections.singletonList(old)).action);
        }
    }

    @Test
    public void eitherCurrencyMakesAnotherAccountsFactExclusive() {
        Purchase incoming = purchase(0, 1, 0, 20, Purchase.SRC_MANUAL);
        for (Purchase old : Arrays.asList(purchase(101, 2, 0, 20, Purchase.SRC_AUTO),
                purchase(101, 2, 20, 0, Purchase.SRC_MANUAL))) {
            assertEquals(LedgerWritePolicy.Action.REJECT,
                    LedgerWritePolicy.decide(incoming, Collections.singletonList(old)).action);
        }
    }

    @Test
    public void zeroCostOwnedOfAnotherAccountDoesNotClaimExclusivePaidOwnership() {
        Purchase incoming = purchase(0, 1, 0, 20, Purchase.SRC_AUTO);
        Purchase owned = purchase(101, 2, 0, 0, Purchase.SRC_OWNED);
        assertEquals(LedgerWritePolicy.Action.INSERT,
                LedgerWritePolicy.decide(incoming, Collections.singletonList(owned)).action);
        Purchase free = purchase(0, 3, 0, 0, Purchase.SRC_OWNED);
        assertEquals(LedgerWritePolicy.Action.INSERT,
                LedgerWritePolicy.decide(free, Collections.singletonList(
                        purchase(102, 1, 0, 20, Purchase.SRC_AUTO))).action);
    }

    @Test
    public void freeBackfillNeverOverwritesPaidAndGenericWritesNeverPromoteOwned() {
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decide(
                purchase(0, 1, 0, 0, Purchase.SRC_OWNED), Collections.singletonList(
                        purchase(101, 1, 0, 20, Purchase.SRC_AUTO))).action);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decide(
                purchase(0, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL), Collections.singletonList(
                        purchase(101, 1, 0, 0, Purchase.SRC_OWNED))).action);
    }

    @Test
    public void unknownInputsAndNewRowsWithOldIdsAreRejected() {
        Purchase valid = purchase(0, 1, 0, 20, Purchase.SRC_AUTO);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decide(null, null).action);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decide(valid, null).action);
        assertEquals(LedgerWritePolicy.Action.REJECT,
                LedgerWritePolicy.decide(valid, Collections.singletonList(null)).action);
        List<Consumer<Purchase>> edits = Arrays.asList(row -> row.id = 5, row -> row.accountId = -1,
                row -> row.chapterId = -1, row -> row.costCoupons = -1,
                row -> row.costVouchers = -1, row -> row.purchasedAt = 0);
        for (Consumer<Purchase> edit : edits) {
            Purchase invalid = purchase(0, 1, 0, 20, Purchase.SRC_AUTO);
            edit.accept(invalid);
            assertEquals(LedgerWritePolicy.Action.REJECT,
                    LedgerWritePolicy.decide(invalid, Collections.emptyList()).action);
        }
    }

    @Test
    public void existingDuplicateOwnershipNeverGetsSilentlyNormalized() {
        Purchase mine = purchase(101, 1, 0, 20, Purchase.SRC_AUTO);
        Purchase other = purchase(102, 2, 0, 20, Purchase.SRC_AUTO);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decide(
                purchase(0, 1, 0, 20, Purchase.SRC_AUTO), Arrays.asList(mine, other)).action);
        assertEquals(LedgerWritePolicy.Action.REJECT, LedgerWritePolicy.decide(
                purchase(0, 1, 0, 20, Purchase.SRC_AUTO), Arrays.asList(mine, mine)).action);
    }

    @Test
    public void ownedPromotionRequiresRemoteFactButAllowsOtherPaidAccounts() {
        Purchase owned = purchase(101, 1, 0, 0, Purchase.SRC_OWNED);
        Purchase remote = purchase(0, 1, 0, 20, Purchase.SRC_REMOTE_DETAIL);
        assertTrue(LedgerWritePolicy.canPromoteOwned(owned, remote, Collections.singletonList(owned)));
        assertTrue(LedgerWritePolicy.canPromoteOwned(owned, remote,
                Arrays.asList(owned, purchase(102, 2, 0, 20, Purchase.SRC_AUTO))));
        assertTrue(LedgerWritePolicy.canPromoteOwned(owned, remote,
                Arrays.asList(owned, purchase(102, 2, 0, 0, Purchase.SRC_OWNED))));
        remote.source = Purchase.SRC_AUTO;
        assertFalse(LedgerWritePolicy.canPromoteOwned(owned, remote, Collections.singletonList(owned)));
        remote.source = Purchase.SRC_MANUAL;
        assertFalse(LedgerWritePolicy.canPromoteOwned(owned, remote, Collections.singletonList(owned)));
        remote.source = Purchase.SRC_REMOTE_DETAIL;
        assertFalse(LedgerWritePolicy.canPromoteOwned(owned, remote, Arrays.asList(owned, owned)));
    }

    @Test
    public void everyCatalogColumnParticipatesInTheDeletionSnapshot() {
        List<Consumer<Chapter>> edits = Arrays.asList(row -> row.id++, row -> row.novelId++,
                row -> row.chapterNo++, row -> row.title += "改", row -> row.volumeTitle += "改",
                row -> row.sfChapterId += "改",
                row -> row.priceCoupons++);
        assertTrue(LedgerWritePolicy.sameChapters(Collections.singletonList(chapter()),
                Collections.singletonList(chapter())));
        for (Consumer<Chapter> edit : edits) {
            Chapter current = chapter();
            edit.accept(current);
            assertFalse(LedgerWritePolicy.sameChapters(Collections.singletonList(chapter()),
                    Collections.singletonList(current)));
        }
        assertFalse(LedgerWritePolicy.sameChapters(null, Collections.emptyList()));
    }

    @Test
    public void changingOnlyTheVolumeInvalidatesTheRemoteRecoverySnapshot() {
        Chapter current = chapter();
        current.volumeTitle = "另一卷";
        assertFalse(SubscriptionDao.sameChapterSnapshot(Collections.singletonList(chapter()),
                Collections.singletonList(current)));
    }

    @Test
    public void everyPurchaseRowColumnParticipatesInTheDeletionSnapshot() {
        List<Consumer<PurchaseRow>> edits = Arrays.asList(row -> row.purchaseId++, row -> row.accountId++,
                row -> row.chapterId++, row -> row.costCoupons++, row -> row.costVouchers++,
                row -> row.purchasedAt++, row -> row.source += "改", row -> row.chapterNo++,
                row -> row.chapterTitle += "改", row -> row.novelTitle += "改", row -> row.accountLabel += "改",
                row -> row.accountNickname += "改", row -> row.accountLoginName += "改");
        assertTrue(LedgerWritePolicy.sameRows(Collections.singletonList(row()), Collections.singletonList(row())));
        for (Consumer<PurchaseRow> edit : edits) {
            PurchaseRow current = row();
            edit.accept(current);
            assertFalse(LedgerWritePolicy.sameRows(Collections.singletonList(row()),
                    Collections.singletonList(current)));
        }
        assertFalse(LedgerWritePolicy.sameRows(null, Collections.emptyList()));
        assertFalse(LedgerWritePolicy.sameRows(Arrays.asList(row(), row()), Arrays.asList(row(), row())));
    }

    private static Chapter chapter() {
        Chapter chapter = new Chapter();
        chapter.id = 50;
        chapter.novelId = 9;
        chapter.chapterNo = 2;
        chapter.title = "题目";
        chapter.volumeTitle = "卷一";
        chapter.sfChapterId = "sf-2";
        chapter.priceCoupons = 20;
        return chapter;
    }

    private static PurchaseRow row() {
        PurchaseRow row = new PurchaseRow();
        row.purchaseId = 101;
        row.accountId = 1;
        row.chapterId = 50;
        row.costCoupons = 0;
        row.costVouchers = 20;
        row.purchasedAt = 1_700_000_000_000L;
        row.source = Purchase.SRC_AUTO;
        row.chapterNo = 2;
        row.chapterTitle = "题目";
        row.novelTitle = "测试书";
        row.accountLabel = "备注";
        row.accountNickname = "昵称";
        row.accountLoginName = "login";
        return row;
    }
}
