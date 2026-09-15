package com.example.blb.auto;

import android.content.Context;

import com.example.blb.data.Account;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.ui.LedgerEdits;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/** 整本目录只在用户明确要求时读一次，不能让八个号为了同一份章节顺序各扫一遍。 */
public final class CatalogQueue {
    public static final class Summary {
        public String book;
        public boolean ok;
        public int scanned = -1;
        public int added, realigned, backfilled, free, accounts;
        public String abortReason;
        public String catalogNote;
        public final List<String> notes = new ArrayList<>();

        public boolean aborted() { return abortReason != null; }
    }

    private CatalogQueue() { }

    /** 八个号共用目录；用户打开过期补扫后，也只许第一个号承担这一次读屏。 */
    static SubscribeRun.Plan syncIfExpired(StepRunner runner, StepRunner.Host host,
                                           SubscriptionDao subs, SubscribeRun.Plan plan,
                                           Account account, int accountIndex)
            throws StepRunner.StepFailure {
        Context context = runner.context();
        if (!CatalogStatus.shouldAutoSync(Prefs.isCatalogAutoSync(context), accountIndex,
                plan.catalogScannedAt, System.currentTimeMillis(), Prefs.catalogMaxAgeHours(context))) {
            return plan;
        }
        host.log("已开启过期自动同步：用第一个号「" + account.displayName() + "」补扫一次目录");
        StepRunner.StepFailure pending = null;
        SubscribeRun.Plan refreshed = null;
        try {
            runner.ensureHome(4);
            CatalogSync.Report report = CatalogSync.sync(runner, subs, plan.novel, account.id);
            host.log(report.message);
            if (!report.ok) throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                    "目录同步未通过完整性检查，整趟停止：" + report.message);
            Novel latest = subs.novelById(plan.novel.id);
            if (latest == null) throw new StepRunner.StepFailure(StepRunner.Kind.CONFIG,
                    "目标小说已不在账本中，整趟停止");
            SubscribeRun.requireCatalog(subs, latest);
            refreshed = new SubscribeRun.Plan(latest, plan.cap, plan.since);
        } catch (StepRunner.StepFailure failure) {
            pending = new StepRunner.StepFailure(stopKind(failure.kind),
                    "目录未能可靠同步，整趟停止：" + failure.getMessage());
        } catch (RuntimeException failure) {
            pending = new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                    "目录同步或写账异常，整趟停止：" + failure.getMessage());
        } finally {
            try {
                runner.ensureHome(4);
            } catch (StepRunner.StepFailure failure) {
                if (pending == null) pending = new StepRunner.StepFailure(stopKind(failure.kind),
                        "目录同步后未能返回首页，整趟停止：" + failure.getMessage());
            } catch (RuntimeException failure) {
                if (pending == null) pending = new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                        "目录同步后页面状态不明，整趟停止：" + failure.getMessage());
            }
        }
        if (pending != null) throw pending;
        return refreshed;
    }

    /** 目录在八个号之间共用，收尾超时也不能被队列当成只跳过当前号的小故障。 */
    static StepRunner.Kind stopKind(StepRunner.Kind kind) {
        return CheckInQueue.isGlobal(kind) ? kind : StepRunner.Kind.MONEY_UNCLEAR;
    }

    public static Summary run(Context context, StepRunner.Host host) {
        Summary out = new Summary();
        AppDatabase db = Db.get(context);
        Novel novel = LedgerEdits.duringRun(host,
                () -> SubscribeRun.resolveTarget(db.subscriptionDao(), host));
        if (novel == null) {
            out.abortReason = "先在订阅页选一本目标小说";
            host.log(out.abortReason);
            return out;
        }
        out.book = novel.title;
        SelectorSet selectors = SelectorSet.load(context);
        host.log(selectors.diagnostics());
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_CATALOG);
        if (!missing.isEmpty()) {
            out.abortReason = selectors.missingMessage(Keys.REQUIRED_FOR_CATALOG);
            host.log(out.abortReason);
            return out;
        }
        StepRunner runner = new StepRunner(context, selectors, host);
        try {
            runner.ensureHome(4);
            long accountId = -1;
            if (!selectors.get(Keys.NICKNAME).isEmpty()) {
                String nickname = runner.readNicknameFromMine();
                List<Account> all = db.accountDao().loadAll();
                int matches = 0;
                for (Account account : all) {
                    if (!Texts.isBlank(nickname) && !Texts.isBlank(account.nickname)
                            && account.nickname.trim().equals(nickname.trim())) {
                        accountId = account.id;
                        matches++;
                    }
                }
                if (matches != 1) accountId = -1;
                runner.ensureHome(4);
            }
            if (accountId < 0) host.log("当前账号身份未能唯一对应；只登记目录与免费章，不猜付费归属");
            CatalogSync.Report report = CatalogSync.sync(runner, db.subscriptionDao(), novel, accountId);
            out.ok = report.ok;
            out.scanned = report.scanned;
            out.added = report.added;
            out.realigned = report.realignedCount;
            out.backfilled = report.backfilled;
            out.free = report.free;
            out.accounts = report.accounts;
            host.log(report.message);
            if (report.realigned != null) out.notes.add(report.realigned);
            out.notes.add(CatalogSync.classificationNote(report));
            if (!report.ok) out.abortReason = report.message;
            Novel latest = db.subscriptionDao().novelById(novel.id);
            out.catalogNote = CatalogStatus.describe(latest, System.currentTimeMillis());
            host.log(out.catalogNote);
        } catch (StepRunner.StepFailure failure) {
            out.abortReason = failure.getMessage();
            host.log(out.abortReason);
        } catch (RuntimeException failure) {
            out.abortReason = "同步目录未完成：" + failure.getMessage();
            host.log(out.abortReason);
        } finally {
            try {
                runner.ensureHome(4);
            } catch (StepRunner.StepFailure failure) {
                String note = "同步目录结束后未能返回首页：" + failure.getMessage();
                out.notes.add(note);
                if (out.abortReason == null) out.abortReason = note;
                host.log(note);
            }
        }
        return out;
    }

    public static String describe(Summary summary) {
        if (summary == null) return "同步目录异常结束";
        if (!summary.ok) return "同步目录未完成：" + summary.abortReason;
        return "目录 " + summary.scanned + " 章（新登记 " + summary.added + "）"
                + "，重排 " + summary.realigned + " 章，免费章补记 " + summary.backfilled + " 条"
                + (summary.abortReason == null ? "；未购买任何章节" : "；中止：" + summary.abortReason);
    }
}
