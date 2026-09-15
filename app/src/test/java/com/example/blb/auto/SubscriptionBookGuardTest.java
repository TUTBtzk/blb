package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Account;
import com.example.blb.data.AccountNovelAudit;
import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 第49章的买家曾记错，任何一个启用号的证明缺失都要拦全书，不能只替当前号过关。 */
public class SubscriptionBookGuardTest {
    private static final long NOVEL = 9;
    private static final long DAY = 86_400_000L;
    private static final long TODAY = 1_999_987_200_000L;
    private static final long NOW = TODAY + 12 * 3_600_000L;

    @Test
    public void allEightAccountsNeedIndependentCompleteEvidence() {
        List<Account> accounts = accounts(8);
        List<AccountNovelAudit> proof = proofs(8);
        assertNull(blocker(accounts, proof));
        for (int missing = 0; missing < 8; missing++) {
            List<AccountNovelAudit> incomplete = new ArrayList<>(proof);
            incomplete.remove(missing);
            String reason = blocker(accounts, incomplete);
            assertNotNull(reason);
            assertTrue(reason, reason.contains("1 个号"));
            assertTrue(reason, reason.contains(accounts.get(missing).displayName()));
            assertTrue(reason, reason.contains("整本暂停购买"));
        }
    }

    @Test
    public void bothAccountsVerifiedForTheSamePaidChapterDoNotBlockTheBook() {
        Chapter chapter = new Chapter();
        chapter.id = 500;
        chapter.novelId = NOVEL;
        chapter.chapterNo = 50;
        chapter.title = "第50章 订婚事宜，梦玲失踪";
        List<PurchaseRow> rows = new ArrayList<>();
        for (int id = 1; id <= 2; id++) {
            PurchaseRow row = new PurchaseRow();
            row.purchaseId = 100 + id;
            row.accountId = id;
            row.chapterId = chapter.id;
            row.chapterNo = chapter.chapterNo;
            row.chapterTitle = chapter.title;
            row.costVouchers = 20;
            row.purchasedAt = RemoteLedgerRecovery.date("2026-09-08");
            row.source = id == 1 ? Purchase.SRC_AUTO : Purchase.SRC_REMOTE_DETAIL;
            rows.add(row);
        }
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "卷一 第50章 订婚事宜，梦玲失踪", "20", "代券", "2026-09-08");
        SubscribedDetail.ReadResult detail = new SubscribedDetail.ReadResult(
                Collections.singletonList(entry), true, true, true, false, 0, 1, 1, "全部覆盖");
        for (int id = 1; id <= 2; id++) {
            VoucherLedger.Audit checked = SubscriptionAuditPolicy.reconcileComplete("账号" + id,
                    "目标书", id, new VoucherLedger.Reading(true, 1, 0, -1, null, null),
                    detail, Collections.singletonList(chapter), rows, NOW);
            assertTrue(checked.message, checked.ok);
            assertTrue(checked.message, checked.checked);
        }
        assertNull(blocker(accounts(2), proofs(2)));
    }

    @Test
    public void everyUnverifiedAccountIsNamedInTheVisibleReason() {
        String reason = blocker(accounts(3), Collections.singletonList(proof(1)));
        assertNotNull(reason);
        assertTrue(reason, reason.contains("2 个号"));
        assertTrue(reason, reason.contains("账号2"));
        assertTrue(reason, reason.contains("账号3"));
        assertTrue(reason, reason.contains("核对订阅清单"));
    }

    @Test
    public void missingInputsCannotProducePermissionToBuy() {
        assertNotNull(blocker(null, proofs(1)));
        assertNotNull(blocker(Collections.emptyList(), proofs(1)));
        assertNotNull(blocker(accounts(1), null));
        assertNotNull(blocker(accounts(1), Collections.emptyList()));
        assertNotNull(blocker(Collections.singletonList(null), proofs(1)));
    }

    @Test
    public void aCompleteReadWithoutASuccessfulMarkerDoesNotAuthorizeAnyAccount() {
        for (String missing : new String[]{null, "", " \t\n"}) {
            AccountNovelAudit previous = proof(2);
            previous.ledgerMarker = missing;
            String reason = blocker(accounts(2), Arrays.asList(proof(1), previous));
            assertNotNull(reason);
            assertTrue(reason, reason.contains("账号2"));
        }
    }

    @Test
    public void yesterdayUnknownAndFutureDetailTimesAreAllUnverified() {
        for (long invalid : new long[]{-1, 0, TODAY - 1, NOW + 1}) {
            AccountNovelAudit previous = proof(1);
            previous.detailAt = invalid;
            assertNotNull("detailAt=" + invalid, blocker(accounts(1), Collections.singletonList(previous)));
        }
        AccountNovelAudit atMidnight = proof(1);
        atMidnight.detailAt = TODAY;
        assertNull(blocker(accounts(1), Collections.singletonList(atMidnight)));
        atMidnight.detailAt = NOW;
        assertNull(blocker(accounts(1), Collections.singletonList(atMidnight)));
    }

    @Test
    public void anUnknownDetailCountIsNotAProofOfZeroSubscriptions() {
        AccountNovelAudit previous = proof(1);
        previous.detailChapters = -1;
        assertNotNull(blocker(accounts(1), Collections.singletonList(previous)));
    }

    @Test
    public void anAggregateReadCannotReplaceTheMissingCompleteDetailRead() {
        AccountNovelAudit aggregateOnly = proof(1);
        aggregateOnly.aggregateAt = NOW;
        aggregateOnly.aggregateChapters = 1;
        aggregateOnly.detailAt = 0;
        aggregateOnly.detailChapters = -1;
        assertNotNull(blocker(accounts(1), Collections.singletonList(aggregateOnly)));
    }

    @Test
    public void aVerifiedEmptyDetailHasADifferentMeaningFromMissingEvidence() {
        AccountNovelAudit empty = proof(1);
        empty.aggregateChapters = 0;
        empty.detailChapters = 0;
        empty.ledgerMarker = "0:0";
        assertNull(blocker(accounts(1), Collections.singletonList(empty)));
    }

    @Test
    public void aDisabledAccountWithoutProofDoesNotBlockEnabledAccounts() {
        Account disabled = account(2);
        disabled.enabled = false;
        assertNull(blocker(Arrays.asList(account(1), disabled), Collections.singletonList(proof(1))));
    }

    @Test
    public void oldProgressOfAnAccountOutsideTheEnabledListDoesNotBlockTheBook() {
        AccountNovelAudit oldDisabled = proof(2);
        oldDisabled.detailAt = TODAY - DAY;
        oldDisabled.ledgerMarker = null;
        assertNull(blocker(accounts(1), Arrays.asList(proof(1), oldDisabled)));
    }

    @Test
    public void aListContainingOnlyDisabledAccountsIsNotAPurchasePermission() {
        Account disabled = account(1);
        disabled.enabled = false;
        assertNotNull(blocker(Collections.singletonList(disabled), Collections.singletonList(proof(1))));
    }

    @Test
    public void unreadableOrDuplicateProgressCannotPretendAllAccountsWereChecked() {
        assertNotNull(blocker(accounts(1), Collections.singletonList(null)));
        assertNotNull(blocker(accounts(1), Arrays.asList(proof(1), proof(1))));
    }

    @Test
    public void aKnownAutoPurchaseKeepsBookPermissionButForcesThisAccountToReadDetailAgain() {
        AccountNovelAudit previous = proof(1);
        previous.ledgerMarker = "1:101";
        long verifiedAt = previous.detailAt;
        // AUTO 增加一条已确认的购买后，数据库当前指纹变了，旧证明仍须保持原样。
        assertNull(blocker(accounts(1), Collections.singletonList(previous)));
        assertTrue(SubscriptionAuditPolicy.mustReadDetail(false, 2, 2, previous,
                "2:102", TODAY, NOW, false));
        assertEquals("1:101", previous.ledgerMarker);
        assertEquals(verifiedAt, previous.detailAt);
        assertEquals(1, previous.detailChapters);
        assertNull(blocker(accounts(1), Collections.singletonList(previous)));
    }

    @Test
    public void clearingOnlyOneAccountsProofBlocksTheWholeBookUntilItIsRechecked() {
        List<AccountNovelAudit> proof = proofs(8);
        assertNull(blocker(accounts(8), proof));
        proof.get(5).ledgerMarker = null;
        String reason = blocker(accounts(8), proof);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("账号6"));
        assertTrue(reason, reason.contains("整本暂停购买"));
    }

    @Test
    public void crossingMidnightCannotReuseThePreviousDaysWholeBookProof() {
        List<AccountNovelAudit> proof = proofs(8);
        assertNull(blocker(accounts(8), proof));
        assertNotNull(SubscriptionAuditPolicy.bookBlocker(accounts(8), proof,
                TODAY + DAY, NOW + DAY));
    }

    private static String blocker(List<Account> accounts, List<AccountNovelAudit> proof) {
        return SubscriptionAuditPolicy.bookBlocker(accounts, proof, TODAY, NOW);
    }

    private static List<Account> accounts(int count) {
        List<Account> accounts = new ArrayList<>();
        for (int id = 1; id <= count; id++) accounts.add(account(id));
        return accounts;
    }

    private static List<AccountNovelAudit> proofs(int count) {
        List<AccountNovelAudit> proof = new ArrayList<>();
        for (int id = 1; id <= count; id++) proof.add(proof(id));
        return proof;
    }

    private static Account account(long id) {
        Account account = new Account();
        account.id = id;
        account.label = "账号" + id;
        account.enabled = true;
        return account;
    }

    private static AccountNovelAudit proof(long accountId) {
        AccountNovelAudit proof = new AccountNovelAudit();
        proof.accountId = accountId;
        proof.novelId = NOVEL;
        proof.aggregateAt = TODAY + 1;
        proof.aggregateChapters = 1;
        proof.detailAt = TODAY + 2;
        proof.detailChapters = 1;
        proof.ledgerMarker = "1:" + (100 + accountId);
        return proof;
    }
}
