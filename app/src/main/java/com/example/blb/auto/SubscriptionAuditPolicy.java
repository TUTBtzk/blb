package com.example.blb.auto;

import com.example.blb.data.Account;
import com.example.blb.data.AccountNovelAudit;
import com.example.blb.data.Chapter;
import com.example.blb.data.PurchaseRow;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 清单没加载出来曾经与零订阅混为一谈；新核对凭证必须把「未核实」留在购买门外。 */
final class SubscriptionAuditPolicy {
    private SubscriptionAuditPolicy() { }

    static String readingProblem(VoucherLedger.Reading reading) {
        if (reading == null || !reading.found) {
            return "清单里没有这本书那一行，这一次没能核对";
        }
        if (reading.chapters < 0) return "清单章数读不到，这一次没能核对";
        if (reading.fire < 0) return "清单火券数读不到，这一次没能核对";
        if (reading.fire > 0) return "清单出现火券支出，整趟停止";
        return null;
    }

    static String detailProblem(VoucherLedger.Reading reading,
                                SubscribedDetail.ReadResult detail) {
        String problem = readingProblem(reading);
        if (problem != null) return problem;
        if (detail != null && detail.fireObserved) {
            return "逐章读取过程中出现火券支出证据，整趟停止";
        }
        if (detail == null || !detail.complete()) {
            return "逐章明细没有完整读取的证据，这一次没能核对"
                    + (detail == null ? "" : "：" + detail.describe());
        }
        if (detail.entries.size() != reading.chapters) {
            return "聚合行说 " + reading.chapters + " 章，完整明细为 "
                    + detail.entries.size() + " 条，两层证据不一致";
        }
        return null;
    }

    static boolean mustReadDetail(boolean force, int aggregateChapters, int paid,
                                  AccountNovelAudit previous, String marker,
                                  long today, long now, boolean willSpend) {
        return force || willSpend || aggregateChapters < 0 || aggregateChapters != paid
                || previous == null || previous.detailAt < today || previous.detailAt <= 0
                || previous.detailAt > now || previous.detailChapters != paid
                || previous.ledgerMarker == null || marker == null
                || !Objects.equals(previous.ledgerMarker, marker);
    }

    static boolean hasFireEvidence(long accountId, VoucherLedger.Reading aggregate,
                                   SubscribedDetail.ReadResult detail, List<PurchaseRow> ledger) {
        if (aggregate != null && aggregate.fireSpent()) return true;
        if (detail != null) {
            if (detail.fireObserved) return true;
            for (SubscribedDetail.Entry entry : detail.entries) {
                if (entry != null && entry.fireSpent()) return true;
            }
        }
        if (ledger != null) {
            for (PurchaseRow row : ledger) {
                if (row != null && row.accountId == accountId && row.costCoupons > 0) return true;
            }
        }
        return false;
    }

    static String localMoneyProblem(long accountId, List<PurchaseRow> rows) {
        if (rows == null) return "本地账本读不到";
        for (PurchaseRow row : rows) {
            if (row == null || (row.accountId == accountId
                    && (row.costCoupons < 0 || row.costVouchers < 0))) {
                return "本地购买金额读不到，这一次没能核对";
            }
        }
        return null;
    }

    /**
     * 2026-09-14 多号真实订同章曾被误报后卡住整书；这里只检查今天的完整核对凭证。
     * 各号明细已核实的跨号重复不使凭证失效，未读取、不完整或过期的证据仍不能授权购买。
     */
    static String bookBlocker(List<Account> enabled, List<AccountNovelAudit> progress,
                               long today, long now) {
        if (enabled == null || enabled.isEmpty() || progress == null) {
            return "账号或核对进度读不到，请先核对订阅清单";
        }
        Map<Long, AccountNovelAudit> byAccount = new HashMap<>();
        for (AccountNovelAudit row : progress) {
            if (row == null || byAccount.put(row.accountId, row) != null) {
                return "核对进度不完整或重复，请先核对订阅清单";
            }
        }
        List<String> unverified = new ArrayList<>();
        int enabledCount = 0;
        for (Account account : enabled) {
            if (account == null) return "启用账号读不到，请先核对订阅清单";
            if (!account.enabled) continue;
            enabledCount++;
            AccountNovelAudit row = byAccount.get(account.id);
            if (row == null || row.detailAt <= 0 || row.detailAt < today || row.detailAt > now
                    || row.detailChapters < 0 || row.ledgerMarker == null
                    || row.ledgerMarker.trim().isEmpty()) {
                unverified.add(account.displayName());
            }
        }
        if (enabledCount == 0) return "没有启用账号，请先核对账号设置";
        if (unverified.isEmpty()) return null;
        return "这本书还有 " + unverified.size() + " 个号今天未核实（"
                + String.join("、", unverified) + "），整本暂停购买；请先点『核对订阅清单』";
    }

    /** 普通核账仍先走旧判据；签发购买凭证时再要求金额、币种、日期和归属都明确。 */
    static VoucherLedger.Audit reconcileComplete(String who, String book, long accountId,
                                                VoucherLedger.Reading reading,
                                                SubscribedDetail.ReadResult detail,
                                                List<Chapter> chapters,
                                                List<PurchaseRow> paidRows, long now) {
        String problem = detailProblem(reading, detail);
        if (problem != null) return new VoucherLedger.Audit(true, false,
                who + " 在《" + book + "》上：" + problem);
        problem = localMoneyProblem(accountId, paidRows);
        if (problem != null) return new VoucherLedger.Audit(true, false,
                who + " 在《" + book + "》上：" + problem);
        VoucherLedger.Audit routine = SubscribedDetail.reconcile(who, accountId, book,
                detail.entries, chapters, paidRows, now);
        if (!routine.ok || !routine.checked) return routine;
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll(
                who + " 在《" + book + "》上：", detail.entries, chapters);
        if (!resolved.ok) return new VoucherLedger.Audit(true, false, resolved.message);
        return RemoteLedgerRecovery.auditResolved(who, book, accountId,
                resolved.resolved, paidRows, now);
    }
}
