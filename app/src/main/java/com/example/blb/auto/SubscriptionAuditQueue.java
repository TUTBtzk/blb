package com.example.blb.auto;

import android.content.Context;

import com.example.blb.data.Account;
import com.example.blb.data.AccountNovelAudit;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.AuditDao;
import com.example.blb.data.Chapter;
import com.example.blb.data.Db;
import com.example.blb.data.LedgerAudit;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.ui.LedgerEdits;
import com.example.blb.util.Prefs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 2026-09-14 用户确认多号重复订阅是真实历史；逐号明细与本号账本对上就签发凭证，
 * 不能把已核实的跨号事实误记为存疑。任何未读全的账号仍会阻止整本继续购买。
 */
public final class SubscriptionAuditQueue {
    public static final class Summary {
        public int total, checked, backfilled, deleted, suspect, unverified;
        public String book, abortReason, nextChapterNote;
        public final List<String> notes = new ArrayList<>();
        public boolean aborted() { return abortReason != null; }
    }

    static final class AccountResult {
        boolean verified;
        boolean suspect;
        StepRunner.Kind failureKind;
        int backfilled, deleted;
        String message;
        VoucherLedger.Reading aggregate;
        SubscribedDetail.ReadResult detail;
        long aggregateAt;
        long detailAt;
    }

    private static final class Snapshot {
        final List<Chapter> chapters;
        final List<PurchaseRow> paid;
        final List<PurchaseRow> all;

        Snapshot(AppDatabase db, long novelId) {
            chapters = db.subscriptionDao().loadChapters(novelId);
            paid = db.subscriptionDao().loadPaidRowsOfNovel(novelId);
            all = db.auditDao().loadAllRowsOfNovel(novelId);
        }
    }

    private SubscriptionAuditQueue() { }

    static final class DetailAttempt {
        final VoucherLedger.Reading aggregate;
        final SubscribedDetail.ReadResult detail;
        final long aggregateAt, detailAt;

        DetailAttempt(VoucherLedger.Reading aggregate, SubscribedDetail.ReadResult detail,
                      long aggregateAt, long detailAt) {
            this.aggregate = aggregate;
            this.detail = detail;
            this.aggregateAt = aggregateAt;
            this.detailAt = detailAt;
        }
    }

    interface DetailAttemptReader {
        DetailAttempt read(int attempt) throws StepRunner.StepFailure;
        void observed(int attempt, DetailAttempt result) throws StepRunner.StepFailure;
    }

    /** v1.1 的 w9899 首屏失败后未复读；两次尝试各自带聚合与明细，不允许拼接残缺证据。 */
    static DetailAttempt readDetailWithRetry(DetailAttemptReader reader) throws StepRunner.StepFailure {
        DetailAttempt last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            last = reader.read(attempt);
            reader.observed(attempt, last);
            if (last == null) return null;
            if ((last.aggregate != null && last.aggregate.fireSpent())
                    || (last.detail != null && last.detail.fireObserved)
                    || SubscriptionAuditPolicy.detailProblem(last.aggregate, last.detail) == null) return last;
        }
        return last;
    }

    public static Summary run(Context context, StepRunner.Host host) {
        Summary summary = new Summary();
        AppDatabase db = Db.get(context);
        Novel novel = LedgerEdits.duringRun(host,
                () -> SubscribeRun.resolveTarget(db.subscriptionDao(), host));
        if (novel == null) {
            summary.abortReason = "先在订阅页选一本目标小说";
            log(host, summary.abortReason);
            return summary;
        }
        summary.book = novel.title;
        List<Account> accounts = db.accountDao().loadEnabled();
        summary.total = accounts.size();
        SelectorSet selectors = SelectorSet.load(context);
        log(host, selectors.diagnostics());
        StepRunner runner = new StepRunner(context, selectors, host);
        int processed = 0;
        // 半路被系统杀掉不能继续借用上次的成功摘要；本轮未走到的号仍须明确挡住购买。
        try {
            LedgerEdits.duringRun(host, () -> { db.auditDao().invalidateNovel(novel.id); return null; });
            if (accounts.isEmpty()) log(host, "没有启用账号，这一次未核对任何账号");
            for (int i = 0; i < accounts.size(); i++) {
                if (host.isCancelled()) {
                    summary.abortReason = "已取消";
                    break;
                }
                Account account = accounts.get(i);
                AccountResult result;
                log(host, "[" + (i + 1) + "/" + accounts.size() + "] 核对 " + account.displayName());
                try {
                    List<String> missing = selectors.missing(Keys.REQUIRED_FOR_AUDIT);
                    if (!missing.isEmpty()) {
                        result = unverified(db, host, novel, account, null,
                                selectors.missingMessage(Keys.REQUIRED_FOR_AUDIT), false);
                    } else {
                        AccountSwitcher.ensureLoggedIn(runner, account, db.accountDao(),
                                accounts.size() == 1);
                        result = auditAccount(runner, host, db, novel, account, true, true, false);
                    }
                } catch (StepRunner.StepFailure failure) {
                    result = unverified(db, host, novel, account, null,
                            failure.getMessage(), failure.kind == StepRunner.Kind.MONEY_UNCLEAR);
                    if (CheckInQueue.isGlobal(failure.kind)) summary.abortReason = failure.getMessage();
                } catch (RuntimeException failure) {
                    result = unverified(db, host, novel, account, null,
                            "核对未完成：" + failure.getMessage(), true);
                    summary.abortReason = "核对证据或账本写入异常，已停止：" + failure.getMessage();
                }
                if (result.failureKind != null && CheckInQueue.isGlobal(result.failureKind)) {
                    summary.abortReason = result.message;
                }
                processed++;
                if (result.verified) summary.checked++;
                else if (result.suspect) summary.suspect++;
                else summary.unverified++;
                summary.backfilled += result.backfilled;
                summary.deleted += result.deleted;
                summary.notes.add(result.message);
                log(host, "  " + result.message);
                if (summary.aborted()) break;
            }
        } catch (RuntimeException failure) {
            summary.abortReason = "核对证据未能保存，整趟停止：" + failure.getMessage();
            log(host, summary.abortReason);
        } finally {
            summary.unverified += accounts.size() - processed;
            try {
                runner.ensureHome(4);
            } catch (StepRunner.StepFailure failure) {
                noteCleanupFailure(summary, "核对结束后未能返回首页：" + failure.getMessage());
                log(host, summary.abortReason);
            } catch (RuntimeException failure) {
                noteCleanupFailure(summary, "核对收尾异常：" + failure.getMessage());
                log(host, summary.abortReason);
            }
            try {
                summary.nextChapterNote = nextChapterNote(db.subscriptionDao(), novel);
            } catch (RuntimeException failure) {
                summary.nextChapterNote = "下一章：账本读取失败，不能继续购买";
                noteCleanupFailure(summary, "核对后读取账本失败：" + failure.getMessage());
            }
            log(host, summary.nextChapterNote);
            String stamp = new SimpleDateFormat("HH:mm", Locale.CHINA).format(System.currentTimeMillis());
            String status = "最后核对 今天 " + stamp + " · " + summary.checked + "/" + summary.total
                    + " 个号 · 补记 " + summary.backfilled + " · 修正 " + summary.deleted
                    + " · 存疑 " + summary.suspect + " · 未核对 " + summary.unverified;
            if (summary.aborted()) status += " · 已中止";
            final String savedStatus = status;
            try {
                LedgerEdits.duringRun(host, () -> {
                    Prefs.setLastAuditSummary(context, novel.id, savedStatus);
                    return null;
                });
            } catch (RuntimeException failure) {
                noteCleanupFailure(summary, "核对摘要未能保存：" + failure.getMessage());
                log(host, summary.abortReason);
            }
        }
        return summary;
    }

    /** 日常购买沿用同一核对入口，但只有独立核对按钮才提交删除计划。 */
    static AccountResult auditAccount(StepRunner runner, StepRunner.Host host, AppDatabase db,
                                      Novel novel, Account account, boolean allowDeletion,
                                      boolean forceDetail, boolean willSpend)
            throws StepRunner.StepFailure {
        AccountNovelAudit previous = db.auditDao().auditFor(account.id, novel.id);
        AccountResult result = new AccountResult();
        clearMarker(db, host, novel, account);
        List<String> missing = runner.selectors().missing(Keys.REQUIRED_FOR_AUDIT);
        if (!missing.isEmpty()) return unverified(db, host, novel, account, result,
                runner.selectors().missingMessage(Keys.REQUIRED_FOR_AUDIT), false);
        try {
            VoucherLedger.Located located = VoucherLedger.locate(runner, novel.title);
            result.aggregate = located.reading;
            result.aggregateAt = System.currentTimeMillis();
            Snapshot before = snapshot(db, novel.id);
            int paid = db.subscriptionDao().countPaidPurchases(account.id, novel.id);
            int fire = db.subscriptionDao().sumFireSpent(account.id, novel.id);
            checkFire(account, novel, result.aggregate, null, before.paid);
            String problem = SubscriptionAuditPolicy.readingProblem(result.aggregate);
            if (problem != null) return unverified(db, host, novel, account, result, problem, false);
            problem = SubscriptionAuditPolicy.localMoneyProblem(account.id, before.all);
            if (problem != null) return unverified(db, host, novel, account, result, problem, true);
            VoucherLedger.Audit aggregate = VoucherLedger.reconcile(account.displayName(), novel.title,
                    result.aggregate, paid, fire);
            log(host, "  " + aggregate.message);
            boolean readDetail = SubscriptionAuditPolicy.mustReadDetail(forceDetail,
                    result.aggregate.chapters, paid, previous,
                    db.auditDao().paidLedgerMarker(account.id, novel.id),
                    SubscribeRun.startOfToday(), System.currentTimeMillis(), willSpend);
            if (!readDetail) {
                result.message = account.displayName() + " 在《" + novel.title + "》上：这个号 "
                        + ((System.currentTimeMillis() - previous.detailAt) / 60_000L)
                        + " 分钟前已逐章核过，账本没变，跳过逐章";
                return verified(db, host, novel, account, result, before);
            }
            DetailAttempt read = readDetailWithRetry(new DetailAttemptReader() {
                @Override public DetailAttempt read(int attempt) throws StepRunner.StepFailure {
                    VoucherLedger.Located current = attempt == 1 ? located
                            : VoucherLedger.locate(runner, novel.title);
                    long aggregateAt = attempt == 1 ? result.aggregateAt : System.currentTimeMillis();
                    checkFire(account, novel, current.reading, null, before.paid);
                    String unreadable = SubscriptionAuditPolicy.readingProblem(current.reading);
                    if (unreadable != null) return new DetailAttempt(current.reading, null, aggregateAt, 0);
                    SubscribedDetail.ReadResult detail = SubscribedDetail.readWithEvidence(runner,
                            current.row, novel.title, before.chapters, current.reading.chapters);
                    return new DetailAttempt(current.reading, detail, aggregateAt, System.currentTimeMillis());
                }

                @Override public void observed(int attempt, DetailAttempt observed)
                        throws StepRunner.StepFailure {
                    log(host, "  " + (attempt == 1 ? "首次" : "重新打开后第二次") + "聚合："
                            + (observed.aggregate == null ? "读不到" : observed.aggregate.describe()));
                    logEvidence(host, attempt == 1 ? "第一次" : "重新打开后第二次",
                            observed.detail, before.chapters);
                    checkFire(account, novel, observed.aggregate, observed.detail, before.paid);
                    String reason = SubscriptionAuditPolicy.detailProblem(observed.aggregate, observed.detail);
                    if (reason != null) log(host, "  第 " + attempt + " 次未完整原因：" + reason
                            + (attempt == 1 ? "；重新取得聚合行并独立重读一次" : "；本账号保持未核实"));
                }
            });
            result.aggregate = read.aggregate;
            result.aggregateAt = read.aggregateAt;
            result.detail = read.detail;
            result.detailAt = read.detailAt;
            checkFire(account, novel, result.aggregate, result.detail, before.paid);
            problem = SubscriptionAuditPolicy.detailProblem(result.aggregate, result.detail);
            if (problem != null) return unverified(db, host, novel, account, result, problem, false);
            if (result.aggregate.chapters > paid) {
                RemoteLedgerRecovery.Plan recovery = RemoteLedgerRecovery.plan(account.displayName(),
                        novel.title, account.id, result.aggregate.chapters, result.detail.entries,
                        before.chapters, before.paid);
                log(host, "  " + recovery.message);
                if (!recovery.ok) return unverified(db, host, novel, account, result,
                        recovery.message, true);
                boolean committed = LedgerEdits.duringRun(host, () -> db.runInTransaction(() -> {
                    if (!db.auditDao().snapshotMatches(novel.id, before.chapters, before.paid, before.all)) {
                        return false;
                    }
                    // 原路径的完整付费快照不可过滤；过滤掉矛盾记录会把混合错账伪装成纯漏记。
                    if (!db.subscriptionDao().restoreRemotePurchases(account.id, novel.id,
                            recovery.purchases, before.chapters, before.paid)) return false;
                    List<PurchaseRow> after = db.subscriptionDao().loadPaidRowsOfNovel(novel.id);
                    VoucherLedger.Audit checked = SubscriptionAuditPolicy.reconcileComplete(
                            account.displayName(), novel.title, account.id, result.aggregate,
                            result.detail, before.chapters, after, System.currentTimeMillis());
                    VoucherLedger.Audit count = VoucherLedger.reconcile(account.displayName(), novel.title,
                            result.aggregate, db.subscriptionDao().countPaidPurchases(account.id, novel.id),
                            db.subscriptionDao().sumFireSpent(account.id, novel.id));
                    if (!checked.ok || !checked.checked || !count.ok || !count.checked) {
                        throw new IllegalStateException("补账二次核对未通过：" + checked.message);
                    }
                    for (Purchase purchase : recovery.purchases) {
                        PurchaseRow restored = findRow(after, account.id, purchase.chapterId);
                        if (restored == null) throw new IllegalStateException("补记后原始记录读不到");
                        db.auditDao().recordBackfill(novel.id, restored,
                                recovery.backfillNotes.get(purchase.chapterId),
                                System.currentTimeMillis());
                    }
                    return true;
                }));
                if (!committed) return unverified(db, host, novel, account, result,
                        "补记前账本快照已变化，一条都没有改", true);
                result.backfilled = recovery.purchases.size();
                for (String note : recovery.backfillNotes.values()) log(host, "  " + note);
                log(host, nextChapterNote(db.subscriptionDao(), novel));
            } else if (result.aggregate.chapters < paid) {
                if (!allowDeletion) return unverified(db, host, novel, account, result,
                        "账本比清单多 " + (paid - result.aggregate.chapters)
                                + " 条，请先点『核对订阅清单』取得两次完整证据", true);
                RemoteLedgerRepair.Plan first = RemoteLedgerRepair.deletionPlan(account.displayName(),
                        novel.title, account.id, result.aggregate, result.detail,
                        before.chapters, before.all, System.currentTimeMillis());
                log(host, "  删除判定：" + first.message);
                if (!first.ok) return unverified(db, host, novel, account, result, first.message, true);
                // 节点和第一页缓存都不能重用；重新走清单入口，第二次独立读到缺席才允许提交。
                runner.ensureHome(4);
                VoucherLedger.Located again = VoucherLedger.locate(runner, novel.title);
                result.aggregate = again.reading;
                result.aggregateAt = System.currentTimeMillis();
                checkFire(account, novel, result.aggregate, null, before.paid);
                String secondProblem = SubscriptionAuditPolicy.readingProblem(result.aggregate);
                if (secondProblem != null) return unverified(db, host, novel, account, result,
                        "删前复读：" + secondProblem + "；一条都没有删", true);
                result.detail = SubscribedDetail.readWithEvidence(runner, again.row, novel.title,
                        before.chapters, result.aggregate.chapters);
                result.detailAt = System.currentTimeMillis();
                logEvidence(host, "删前第二次", result.detail, before.chapters);
                checkFire(account, novel, result.aggregate, result.detail, before.paid);
                RemoteLedgerRepair.Plan second = RemoteLedgerRepair.deletionPlan(account.displayName(),
                        novel.title, account.id, result.aggregate, result.detail,
                        before.chapters, before.all, System.currentTimeMillis());
                if (!RemoteLedgerRepair.sameDeletionPlan(first, second)) {
                    return unverified(db, host, novel, account, result,
                            "两次删除证据未完全一致，一条都没有删：" + second.message, true);
                }
                AuditDao.RepairResult commit = LedgerEdits.duringRun(host,
                        () -> db.auditDao().commitDeletion(first, second,
                                before.chapters, before.paid, before.all, System.currentTimeMillis()));
                if (!commit.ok) return unverified(db, host, novel, account, result, commit.message, true);
                result.deleted = commit.deleted;
                log(host, "  " + commit.message);
                log(host, nextChapterNote(db.subscriptionDao(), novel));
            }
            Snapshot after = snapshot(db, novel.id);
            VoucherLedger.Audit checked = SubscriptionAuditPolicy.reconcileComplete(
                    account.displayName(), novel.title, account.id, result.aggregate, result.detail,
                    after.chapters, after.paid, System.currentTimeMillis());
            if (!checked.ok || !checked.checked) return unverified(db, host, novel, account,
                    result, checked.message, !checked.ok);
            result.message = checked.message + (result.backfilled == 0 ? ""
                    : "；账本漏记 " + result.backfilled + " 章，已补回并二次核对")
                    + (result.deleted == 0 ? "" : "；两次确认后修正 " + result.deleted + " 条，可撤销");
            return verified(db, host, novel, account, result, after);
        } catch (StepRunner.StepFailure failure) {
            result.failureKind = failure.kind;
            return unverified(db, host, novel, account, result,
                    failure.getMessage(), failure.kind == StepRunner.Kind.MONEY_UNCLEAR);
        } catch (RuntimeException failure) {
            result.failureKind = StepRunner.Kind.MONEY_UNCLEAR;
            return unverified(db, host, novel, account, result,
                    "账本或核对证据处理异常，已停止：" + failure.getMessage(), true);
        } finally {
            try {
                runner.ensureHome(4);
            } catch (StepRunner.StepFailure failure) {
                // 扣券后收尾失败曾导致漏账；已提交的补记/修正数量也不能被返回首页的异常盖掉。
                log(host, "  核对后未能返回首页：" + failure.getMessage());
                if (result.failureKind == null) {
                    result.failureKind = failure.kind;
                    unverified(db, host, novel, account, result,
                            "核对后未能返回首页：" + failure.getMessage(), false);
                }
            } catch (RuntimeException failure) {
                log(host, "  核对收尾异常：" + failure.getMessage());
                if (result.failureKind == null) {
                    result.failureKind = StepRunner.Kind.MONEY_UNCLEAR;
                    unverified(db, host, novel, account, result,
                            "核对收尾异常：" + failure.getMessage(), true);
                }
            }
        }
    }

    private static void logEvidence(StepRunner.Host host, String label,
                                    SubscribedDetail.ReadResult detail, List<Chapter> chapters) {
        log(host, "  " + label + "明细证据：" + (detail == null ? "读不到" : detail.describe()));
        if (detail != null) {
            for (SubscribedDetail.Entry entry : detail.entries) {
                if (entry != null) log(host, "    明细：" + mappedDescription(entry, chapters));
            }
        }
    }

    static String mappedDescription(SubscribedDetail.Entry entry, List<Chapter> chapters) {
        RemoteLedgerRecovery.Resolution resolution = RemoteLedgerRecovery.resolveAll("",
                Collections.singletonList(entry), chapters);
        String position = resolution.ok && resolution.resolved.size() == 1
                ? "全书第 " + resolution.resolved.get(0).chapter.chapterNo + " 章"
                : "全书位置未能唯一映射";
        return entry.describe() + " → " + position;
    }

    private static void checkFire(Account account, Novel novel, VoucherLedger.Reading aggregate,
                                  SubscribedDetail.ReadResult detail, List<PurchaseRow> ledger)
            throws StepRunner.StepFailure {
        if (SubscriptionAuditPolicy.hasFireEvidence(account.id, aggregate, detail, ledger)) {
            throw new StepRunner.StepFailure(
                StepRunner.Kind.MONEY_UNCLEAR, account.displayName() + " 在《" + novel.title
                + "》上出现火券支出证据，整趟停止，请核对；没有删除任何记录");
        }
    }

    private static Snapshot snapshot(AppDatabase db, long novelId) {
        return Db.call(() -> db.runInTransaction(() -> new Snapshot(db, novelId)));
    }

    private static PurchaseRow findRow(List<PurchaseRow> rows, long accountId, long chapterId) {
        PurchaseRow result = null;
        for (PurchaseRow row : rows) {
            if (row.accountId == accountId && row.chapterId == chapterId) {
                if (result != null) return null;
                result = row;
            }
        }
        return result;
    }

    private static void clearMarker(AppDatabase db, StepRunner.Host host, Novel novel, Account account) {
        LedgerEdits.duringRun(host, () -> {
            AccountNovelAudit progress = db.auditDao().auditFor(account.id, novel.id);
            if (progress == null) progress = progressOf(account, novel);
            progress.ledgerMarker = null;
            db.auditDao().saveProgress(progress);
            return null;
        });
    }

    private static AccountNovelAudit progressOf(Account account, Novel novel) {
        AccountNovelAudit progress = new AccountNovelAudit();
        progress.accountId = account.id;
        progress.novelId = novel.id;
        return progress;
    }

    private static AccountResult verified(AppDatabase db, StepRunner.Host host, Novel novel,
                                          Account account, AccountResult result, Snapshot evidence) {
        boolean saved = LedgerEdits.duringRun(host, () -> db.runInTransaction(() -> {
            if (!db.auditDao().snapshotMatches(novel.id, evidence.chapters, evidence.paid, evidence.all)) {
                return false;
            }
            saveProgress(db, novel, account, result, true);
            return true;
        }));
        if (!saved) return unverified(db, host, novel, account, result,
                "核对完之后账本又变了，本次结论未用于购买", true);
        result.verified = true;
        return result;
    }

    private static AccountResult unverified(AppDatabase db, StepRunner.Host host, Novel novel,
                                            Account account, AccountResult incoming, String message,
                                            boolean suspect) {
        AccountResult result = incoming == null ? new AccountResult() : incoming;
        result.verified = false;
        result.suspect = suspect;
        result.message = account.displayName() + " 在《" + novel.title + "》上：" + message;
        try {
            LedgerEdits.duringRun(host, () -> {
                db.runInTransaction(() -> {
                    saveProgress(db, novel, account, result, false);
                    LedgerAudit audit = new LedgerAudit();
                    audit.at = System.currentTimeMillis();
                    audit.accountId = account.id;
                    audit.novelId = novel.id;
                    audit.kind = LedgerAudit.KIND_SUSPECT;
                    audit.title = novel.title;
                    audit.detail = result.message;
                    db.auditDao().insertLedgerAudit(audit);
                });
                return null;
            });
        } catch (RuntimeException failure) {
            result.failureKind = StepRunner.Kind.MONEY_UNCLEAR;
            result.message += "；核对记录未能保存，整趟停止：" + failure.getMessage();
        }
        return result;
    }

    private static void saveProgress(AppDatabase db, Novel novel, Account account,
                                     AccountResult result, boolean verified) {
        AccountNovelAudit progress = db.auditDao().auditFor(account.id, novel.id);
        if (progress == null) progress = progressOf(account, novel);
        if (result.aggregateAt > 0) {
            progress.aggregateAt = result.aggregateAt;
            progress.aggregateChapters = result.aggregate != null && result.aggregate.found
                    ? result.aggregate.chapters : -1;
        }
        if (result.detail != null && result.detail.complete()) {
            progress.detailAt = result.detailAt;
            progress.detailChapters = result.detail.entries.size();
        }
        progress.ledgerMarker = verified ? db.auditDao().paidLedgerMarker(account.id, novel.id) : null;
        db.auditDao().saveProgress(progress);
    }

    static String nextChapterNote(SubscriptionDao subs, Novel novel) {
        return SubscribeRun.nextChapterNote(
                subs.findNextUnownedChapterFrom(novel.id, novel.startFrom()), novel.startFrom());
    }

    private static void noteCleanupFailure(Summary summary, String note) {
        if (summary.abortReason == null) summary.abortReason = note;
        else summary.notes.add(note);
    }

    private static void log(StepRunner.Host host, String message) {
        try {
            host.log(message);
        } catch (RuntimeException failure) {
            // 通知权限变化也会使 host.log 抛错；不能让一条通知盖掉已经提交的修账和最终结论。
            android.util.Log.w("SubscriptionAudit", message, failure);
        }
    }

    public static String describe(Summary summary) {
        if (summary == null) return "核对订阅清单异常结束";
        return "核对 " + summary.checked + "/" + summary.total + " 个号，补记 "
                + summary.backfilled + "，修正 " + summary.deleted + "，存疑 " + summary.suspect
                + "，未核对 " + summary.unverified
                + (summary.nextChapterNote == null ? "" : "；" + summary.nextChapterNote)
                + (summary.aborted() ? "；中止：" + summary.abortReason : "");
    }
}
