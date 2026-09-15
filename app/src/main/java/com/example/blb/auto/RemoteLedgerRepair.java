package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 2026-08-31 的粘连章号曾把真购买报成漏订；删除必须保留完整读取证据，不能沿用一次缺席的猜测。
 * 这里只签发可重验的计划，落库方还须拿第二次独立读取和新鲜账本再次核实。
 */
public final class RemoteLedgerRepair {
    public static final class Plan {
        public final boolean ok;
        public final String message;
        public final int failedCondition;
        public final List<PurchaseRow> deletions;
        public final long accountId;
        public final long novelId;
        public final long plannedAt;
        public final String who;
        public final String book;
        public final VoucherLedger.Reading aggregate;
        public final SubscribedDetail.ReadResult readResult;
        public final List<Chapter> chapters;
        public final List<PurchaseRow> allRowsOfNovel;
        public final String evidenceMarker;

        private Plan(boolean ok, String message, int failedCondition,
                     List<PurchaseRow> deletions, String who, String book, long accountId,
                     long novelId, long now, VoucherLedger.Reading aggregate,
                     SubscribedDetail.ReadResult detail, List<Chapter> chapters,
                     List<PurchaseRow> allRows) {
            this.ok = ok;
            this.message = message;
            this.failedCondition = failedCondition;
            this.deletions = copyRows(deletions);
            this.accountId = accountId;
            this.novelId = novelId;
            this.plannedAt = now;
            this.who = who;
            this.book = book;
            this.aggregate = aggregate;
            this.readResult = detail;
            this.chapters = copyChapters(chapters);
            this.allRowsOfNovel = copyRows(allRows);
            this.evidenceMarker = evidence(aggregate, detail, chapters);
        }
    }

    private RemoteLedgerRepair() {
    }

    public static Plan deletionPlan(String who, String book, long accountId,
                                    VoucherLedger.Reading aggregate,
                                    SubscribedDetail.ReadResult detail,
                                    List<Chapter> chapters, List<PurchaseRow> allRowsOfNovel,
                                    long now) {
        String head = who + " 在《" + book + "》上的账本暂不能自动删除：";
        long novelId = novelId(chapters);
        if (aggregate == null || !aggregate.found || aggregate.chapters < 0
                || aggregate.fire != 0) {
            String reason = aggregate == null || !aggregate.found
                    ? "清单里没有这本书那一行，这一次没能核对"
                    : aggregate.chapters < 0 ? "聚合章数读不到"
                    : aggregate.fire > 0 ? "清单记有火券花费，必须先核实"
                    : "清单的火券数字读不到";
            return fail(1, head + reason, who, book, accountId, novelId, now,
                    aggregate, detail, chapters, allRowsOfNovel);
        }
        // v1.1 长列表可能在冲突帧或稳定等待中读到火券；后来保留的代券行不能抹去这份证据。
        if (detail != null && detail.fireObserved) {
            return fail(1, head + "逐章读取过程中出现火券支出证据，必须先核实",
                    who, book, accountId, novelId, now, aggregate, detail, chapters, allRowsOfNovel);
        }
        if (detail == null || !detail.complete()) {
            return fail(2, head + (detail == null ? "明细没有完整读取证据" : detail.describe()),
                    who, book, accountId, novelId, now, aggregate, detail, chapters, allRowsOfNovel);
        }
        if (detail.entries.size() != aggregate.chapters) {
            return fail(3, head + "聚合是 " + aggregate.chapters + " 章，完整明细是 "
                    + detail.entries.size() + " 条", who, book, accountId, novelId, now,
                    aggregate, detail, chapters, allRowsOfNovel);
        }
        if (accountId <= 0 || novelId <= 0 || allRowsOfNovel == null) {
            return fail(6, head + "账号、整本目录或账本快照不完整", who, book, accountId,
                    novelId, now, aggregate, detail, chapters, allRowsOfNovel);
        }
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll(
                head, detail.entries, chapters);
        if (!resolved.ok) {
            return fail(6, resolved.message, who, book, accountId, novelId, now,
                    aggregate, detail, chapters, allRowsOfNovel);
        }

        Map<Long, Chapter> catalog = new LinkedHashMap<>();
        for (Chapter chapter : chapters) catalog.put(chapter.id, chapter);
        Map<Long, RemoteLedgerRecovery.Resolved> remote = new LinkedHashMap<>();
        for (RemoteLedgerRecovery.Resolved item : resolved.resolved) {
            if (item.entry.fireSpent()) {
                return fail(1, head + "明细记有火券花费，必须先核实", who, book, accountId,
                        novelId, now, aggregate, detail, chapters, allRowsOfNovel);
            }
            remote.put(item.chapter.id, item);
        }
        Map<Long, PurchaseRow> mine = new LinkedHashMap<>();
        Set<String> paidAccountChapters = new HashSet<>();
        Map<Long, PurchaseRow> ids = new LinkedHashMap<>();
        for (PurchaseRow row : allRowsOfNovel) {
            if (row == null || row.purchaseId <= 0 || row.accountId <= 0 || row.chapterId <= 0
                    || !catalog.containsKey(row.chapterId)
                    || ids.put(row.purchaseId, row) != null) {
                return fail(6, head + "账本快照有无效、重复或不属于本书的记录", who, book,
                        accountId, novelId, now, aggregate, detail, chapters, allRowsOfNovel);
            }
            if (!paid(row)) continue;
            // v5 旧账可能保留 -1；只验待删行会漏掉保留章的未知金额，负数还可能掩盖别行火券。
            if (row.accountId == accountId && (row.costCoupons < 0 || row.costVouchers < 0)) {
                return fail(6, head + "全书第" + row.chapterNo
                        + "章的原记录金额读不到，余下账目也未核清，无法保证删除后可撤销",
                        who, book, accountId, novelId, now,
                        aggregate, detail, chapters, allRowsOfNovel);
            }
            if (!paidAccountChapters.add(row.accountId + ":" + row.chapterId)) {
                return fail(6, head + "全书第" + row.chapterNo + "章同一账号有重复付费记录，无法核实快照",
                        who, book, accountId, novelId, now,
                        aggregate, detail, chapters, allRowsOfNovel);
            }
            if (row.accountId == accountId) mine.put(row.chapterId, row);
        }

        int missing = 0;
        for (long chapterId : remote.keySet()) if (!mine.containsKey(chapterId)) missing++;
        List<PurchaseRow> extra = new ArrayList<>();
        for (PurchaseRow row : mine.values()) if (!remote.containsKey(row.chapterId)) extra.add(row);
        // 旧恢复要求本地不能有任何远端缺席章；不能过滤快照后绕过它来完成所谓先补后删。
        if (missing > 0) {
            return fail(0, head + "同时发现账本漏记 " + missing + " 章、远端缺席 "
                    + extra.size() + " 章；本轮只记存疑，不混合补删", who, book, accountId,
                    novelId, now, aggregate, detail, chapters, allRowsOfNovel);
        }
        if (extra.isEmpty()) {
            return fail(0, head + "没有经过缺席证据确认的多记记录", who, book, accountId,
                    novelId, now, aggregate, detail, chapters, allRowsOfNovel);
        }

        for (PurchaseRow row : extra) {
            if (Texts.isBlank(row.source) || Purchase.SRC_OWNED.equals(row.source)) {
                return fail(4, head + "全书第" + row.chapterNo + "章是免费归属或来源读不到",
                        who, book, accountId, novelId, now,
                        aggregate, detail, chapters, allRowsOfNovel);
            }
            if (row.purchasedAt <= 0 || now <= row.purchasedAt
                    || now - row.purchasedAt <= SubscribedDetail.FRESH_MS) {
                return fail(5, head + "全书第" + row.chapterNo
                        + "章的购买时间未知、在未来或尚未严格超过 15 分钟", who, book,
                        accountId, novelId, now, aggregate, detail, chapters, allRowsOfNovel);
            }
            Chapter chapter = catalog.get(row.chapterId);
            if (chapter == null || row.chapterNo != chapter.chapterNo
                    || Texts.isBlank(row.chapterTitle) || Texts.isBlank(chapter.title)
                    || (!SubscribedDetail.sameChapter(row.chapterTitle, chapter.title)
                    && !SubscribedDetail.sameChapter(row.chapterTitle,
                    Texts.chapterTitle(chapter.title)))) {
                return fail(6, head + "全书第" + row.chapterNo
                        + "章的原记录不能用章号和标题唯一对应本地目录", who, book, accountId,
                        novelId, now, aggregate, detail, chapters, allRowsOfNovel);
            }
            for (PurchaseRow other : allRowsOfNovel) {
                if (other.chapterId == row.chapterId && other.accountId != accountId) {
                    // 2026-09-14 已确认跨号重复可能是真实历史；条件⑦仍保留，只是不再称它错账。
                    return fail(7, head + "全书第" + row.chapterNo
                            + "章：这一章在别的号名下也有记录，不自动删（「"
                            + other.accountDisplayName() + "」，包括免费归属）",
                            who, book, accountId, novelId, now,
                            aggregate, detail, chapters, allRowsOfNovel);
                }
            }
        }

        List<PurchaseRow> retained = new ArrayList<>();
        for (PurchaseRow row : allRowsOfNovel) {
            if (row.accountId != accountId || remote.containsKey(row.chapterId)) retained.add(row);
        }
        VoucherLedger.Audit remaining = RemoteLedgerRecovery.auditResolved(
                who, book, accountId, resolved.resolved, retained, now);
        if (!remaining.ok || !remaining.checked) {
            return fail(0, head + "余下记录仍未核清；" + remaining.message, who, book,
                    accountId, novelId, now, aggregate, detail, chapters, allRowsOfNovel);
        }
        return new Plan(true, who + " 在《" + book + "》上：" + extra.size()
                + " 条多记记录满足七项删除条件，仍须独立复读及事务复核", 0, extra,
                who, book, accountId, novelId, now, aggregate, detail, chapters, allRowsOfNovel);
    }

    /** 两次章数相同仍可能是两批不同交易，不能只比较计数；复读时间本来就会不同。 */
    public static boolean sameDeletionPlan(Plan first, Plan second) {
        return first != null && second != null && first.ok && second.ok
                && !first.deletions.isEmpty() && !second.deletions.isEmpty()
                && first.accountId == second.accountId && first.novelId == second.novelId
                && Objects.equals(first.evidenceMarker, second.evidenceMarker)
                && rowFacts(first.deletions).equals(rowFacts(second.deletions));
    }

    private static boolean paid(PurchaseRow row) {
        return row.costCoupons > 0 || row.costVouchers > 0;
    }

    private static long novelId(List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return 0;
        long novelId = 0;
        for (Chapter chapter : chapters) {
            if (chapter == null || chapter.novelId <= 0) return 0;
            if (novelId != 0 && novelId != chapter.novelId) return 0;
            novelId = chapter.novelId;
        }
        return novelId;
    }

    private static Plan fail(int condition, String message, String who, String book, long accountId,
                             long novelId, long now, VoucherLedger.Reading aggregate,
                             SubscribedDetail.ReadResult detail, List<Chapter> chapters,
                             List<PurchaseRow> rows) {
        return new Plan(false, "存疑：" + message, condition, Collections.<PurchaseRow>emptyList(),
                who, book, accountId, novelId, now, aggregate, detail, chapters, rows);
    }

    private static String evidence(VoucherLedger.Reading aggregate, SubscribedDetail.ReadResult detail,
                                   List<Chapter> chapters) {
        StringBuilder out = new StringBuilder();
        if (aggregate == null) append(out, null);
        else {
            append(out, String.valueOf(aggregate.found));
            append(out, String.valueOf(aggregate.chapters));
            append(out, String.valueOf(aggregate.fire));
            append(out, String.valueOf(aggregate.voucher));
            append(out, aggregate.date);
            append(out, aggregate.raw);
        }
        Map<SubscribedDetail.Entry, Chapter> mapped = new LinkedHashMap<>();
        if (detail != null) {
            RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll(
                    "", detail.entries, chapters);
            if (resolved.ok) for (RemoteLedgerRecovery.Resolved item : resolved.resolved) {
                mapped.put(item.entry, item.chapter);
            }
        }
        List<String> entries = new ArrayList<>();
        if (detail != null) for (SubscribedDetail.Entry entry : detail.entries) {
            StringBuilder row = new StringBuilder();
            if (entry == null) append(row, null);
            else {
                append(row, String.valueOf(entry.chapterNo));
                append(row, entry.volume);
                append(row, entry.title);
                append(row, String.valueOf(entry.amount));
                append(row, entry.currency);
                append(row, entry.date);
                append(row, entry.raw);
                Chapter chapter = mapped.get(entry);
                append(row, chapter == null ? null : String.valueOf(chapter.id));
                append(row, chapter == null ? null : String.valueOf(chapter.chapterNo));
                append(row, chapter == null ? null : chapter.title);
                append(row, chapter == null ? null : chapter.volumeTitle);
            }
            entries.add(row.toString());
        }
        Collections.sort(entries);
        append(out, String.valueOf(entries.size()));
        for (String entry : entries) append(out, entry);
        return out.toString();
    }

    private static List<String> rowFacts(List<PurchaseRow> rows) {
        List<String> out = new ArrayList<>();
        for (PurchaseRow row : rows) {
            StringBuilder fact = new StringBuilder();
            append(fact, String.valueOf(row.purchaseId));
            append(fact, String.valueOf(row.accountId));
            append(fact, String.valueOf(row.chapterId));
            append(fact, String.valueOf(row.costCoupons));
            append(fact, String.valueOf(row.costVouchers));
            append(fact, String.valueOf(row.purchasedAt));
            append(fact, row.source);
            append(fact, String.valueOf(row.chapterNo));
            append(fact, row.chapterTitle);
            append(fact, row.novelTitle);
            append(fact, row.accountLabel);
            append(fact, row.accountNickname);
            append(fact, row.accountLoginName);
            out.add(fact.toString());
        }
        Collections.sort(out);
        return out;
    }

    /** 长度前缀避免标题中的竖线和换行制造指纹碰撞；证据比较不使用 hashCode。 */
    private static void append(StringBuilder out, String value) {
        if (value == null) out.append("-1:");
        else out.append(value.length()).append(':').append(value);
    }

    private static List<Chapter> copyChapters(List<Chapter> source) {
        List<Chapter> out = new ArrayList<>();
        if (source != null) for (Chapter row : source) {
            if (row == null) continue;
            Chapter copy = new Chapter();
            copy.id = row.id;
            copy.novelId = row.novelId;
            copy.chapterNo = row.chapterNo;
            copy.title = row.title;
            copy.volumeTitle = row.volumeTitle;
            copy.sfChapterId = row.sfChapterId;
            copy.priceCoupons = row.priceCoupons;
            out.add(copy);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<PurchaseRow> copyRows(List<PurchaseRow> source) {
        List<PurchaseRow> out = new ArrayList<>();
        if (source != null) for (PurchaseRow row : source) {
            if (row == null) continue;
            PurchaseRow copy = new PurchaseRow();
            copy.purchaseId = row.purchaseId;
            copy.accountId = row.accountId;
            copy.chapterId = row.chapterId;
            copy.costCoupons = row.costCoupons;
            copy.costVouchers = row.costVouchers;
            copy.purchasedAt = row.purchasedAt;
            copy.source = row.source;
            copy.chapterNo = row.chapterNo;
            copy.chapterTitle = row.chapterTitle;
            copy.novelTitle = row.novelTitle;
            copy.accountLabel = row.accountLabel;
            copy.accountNickname = row.accountNickname;
            copy.accountLoginName = row.accountLoginName;
            out.add(copy);
        }
        return Collections.unmodifiableList(out);
    }
}
