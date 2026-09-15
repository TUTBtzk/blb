package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.ui.LedgerEdits;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅页次要操作里的「只跑订阅」：按启用账号逐个完成订阅。
 *
 * <p>按账号顺序走，一个号在场时把它能买的章一次买完，代券花光了才换下一个 ——
 * 而不是按章换号。换号意味着退登重登，重登是最招验证码的动作，所以每个号只登一次。
 * 每个号具体做什么在 {@link SubscribeRun#oneAccount} 里，和签到队列的第 3 步是同一份代码。
 *
 * <p>花钱的护栏：
 * <ul>
 *   <li>判据是章节页那行「实付」里没有火券（用户不充值火券），读不到一律当买不起；</li>
 *   <li>每个账号每天最多花 {@code dailySpendCap} 代券（0 表示不限）；</li>
 *   <li>停止条件只有「代券不够下一章 → 换号」，所有号都不够就收工 —— 没有章数上限。</li>
 * </ul>
 */
public final class SubscribeQueue {

    public static final class Summary {
        public int total;
        public int bought;
        public int already;
        public int failed;
        /** 实付代券累计。 */
        public int spent;
        public String abortReason;
        public String catalogNote;
        public String nextChapterNote;
        /**
         * 整本现在<b>停在第几章</b>（{@link SubscribeRun#stuckNote}）；没卡住就是 null。
         * 队列绝不越过一章，所以一趟里最多只会停在一章上。代券不够也算，那不是失败。
         */
        public String stuckNote;
        /** 这一趟有没有<b>越过</b>一章（{@link SubscribeRun#gapNote}）；正常永远是 null。 */
        public String gapNote;
        public final List<String> notes = new ArrayList<>();

        public boolean aborted() {
            return abortReason != null;
        }
    }

    private SubscribeQueue() {
    }

    public static Summary run(Context context, StepRunner.Host host) {
        return runOnce(context, host);
    }

    private static Summary runOnce(Context context, StepRunner.Host host) {
        Summary summary = new Summary();
        AppDatabase db = Db.get(context);
        SubscriptionDao subs = db.subscriptionDao();
        AccountDao accountDao = db.accountDao();

        Novel novel;
        try {
            novel = prepare(context, subs, summary, host);
        } catch (StepRunner.StepFailure failure) {
            summary.abortReason = failure.getMessage();
            host.log(summary.abortReason);
            return summary;
        } catch (RuntimeException failure) {
            summary.abortReason = "订阅账本预检失败，整趟停止：" + failure.getMessage();
            host.log(summary.abortReason);
            return summary;
        }
        if (novel == null) return summary;

        SelectorSet selectors = SelectorSet.load(context);
        host.log("选择器来自 " + selectors.source());
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SUBSCRIBE);
        missing.addAll(selectors.missing(Keys.REQUIRED_FOR_SWITCH));
        if (!missing.isEmpty()) {
            summary.abortReason = "selectors.json 缺少订阅必需的 key：" + TextUtils.join("、", missing);
            host.log(summary.abortReason);
            return summary;
        }

        List<Account> accounts = accountDao.loadEnabled();
        summary.total = accounts.size();
        if (accounts.isEmpty()) {
            summary.abortReason = "没有启用的账号，什么都没做";
            host.log(summary.abortReason);
            return summary;
        }

        SubscribeRun.Plan plan = SubscribeRun.Plan.from(context, novel);
        host.log(plan.describe());

        SubscribeRun.Tally tally = new SubscribeRun.Tally(summary.notes);
        StepRunner runner = new StepRunner(context, selectors, host);
        SubscribeRun.Settled settled = new SubscribeRun.Settled();

        try {
            for (int i = 0; i < accounts.size(); i++) {
                if (host.isCancelled()) {
                    summary.abortReason = "已取消";
                    break;
                }
                Account account = accounts.get(i);
                host.log("[" + (i + 1) + "/" + accounts.size() + "] " + account.displayName()
                        + "（" + account.loginKindLabel() + "）");
                try {
                    AccountSwitcher.ensureLoggedIn(runner, account, accountDao, accounts.size() == 1);
                    plan = CatalogQueue.syncIfExpired(runner, host, subs, plan, account, i);
                    summary.catalogNote = CatalogStatus.runNote(plan.novel,
                            System.currentTimeMillis(), Prefs.catalogMaxAgeHours(context));
                    // 余额得从「我的」页读，签到面板上一个数字都没有；读不到就退回账号库里记的代券。
                    Texts.Balance balance = runner.readBalanceFromMine();
                    host.log("  " + balance.describe());
                    if (balance.known()) {
                        if (balance.fire >= 0) account.lastKnownCoupons = balance.fire;
                        if (balance.voucher >= 0) account.lastKnownVouchers = balance.voucher;
                        accountDao.setBalance(account.id, balance.fire, balance.voucher);
                    }
                    SubscribeRun.oneAccount(runner, host, subs, accountDao, plan, account,
                            balance, settled, tally);
                } catch (StepRunner.StepFailure e) {
                    if (isGlobal(e.kind)) summary.abortReason = e.getMessage();
                    host.log("  " + e.getMessage());
                    if (isGlobal(e.kind)) {
                        summary.abortReason = e.getMessage();
                        break;
                    }
                    tally.failed++;
                    tally.notes.add(account.displayName() + "：" + e.getMessage());
                } catch (Exception e) {
                    tally.failed++;
                    host.log("  意外错误：" + e);
                }
            }
        } catch (RuntimeException failure) {
            String note = "流程或失败记录保存异常，整趟停止：" + failure.getMessage();
            if (summary.abortReason == null) summary.abortReason = note;
            else summary.notes.add(note);
        } finally {
            // 后续账号记失败也可能抛错；已经买到的章和花掉的券必须留在同一份结论里。
            summary.bought = tally.bought;
            summary.already = tally.ownedAlready;
            summary.failed = tally.failed;
            summary.spent = tally.spent;
        }
        try {
            Novel latest = subs.novelById(plan.novel.id);
            summary.catalogNote = CatalogStatus.runNote(latest == null ? plan.novel : latest,
                    System.currentTimeMillis(), Prefs.catalogMaxAgeHours(context));
            summary.stuckNote = SubscribeRun.stuckNote(subs, plan, tally);
            summary.gapNote = SubscribeRun.gapNote(subs, plan, tally);
            summary.nextChapterNote = SubscribeRun.currentNextChapterNote(subs, plan.novel);
            host.log(summary.catalogNote);
            host.log(summary.nextChapterNote);
        } catch (RuntimeException failure) {
            summary.nextChapterNote = "下一章：账本读取失败，不能继续购买";
            if (summary.abortReason == null) {
                summary.abortReason = "收尾核对账本失败：" + failure.getMessage();
            }
        }
        return summary;
    }

    /** 跑之前必须成立的几件事。返回 null = 这一轮不跑，原因已经写进 summary。 */
    private static Novel prepare(Context context, SubscriptionDao subs, Summary summary,
                                 StepRunner.Host host) throws StepRunner.StepFailure {
        Novel novel = LedgerEdits.duringRun(host, () -> SubscribeRun.resolveTarget(subs, host));
        if (novel == null) {
            summary.abortReason = "先在订阅页选一本目标小说";
            host.log(summary.abortReason);
        } else {
            summary.catalogNote = CatalogStatus.runNote(novel,
                    System.currentTimeMillis(), Prefs.catalogMaxAgeHours(context));
            summary.nextChapterNote = SubscribeRun.currentNextChapterNote(subs, novel);
            host.log(summary.catalogNote);
            SubscribeRun.requireCatalog(subs, novel);
        }
        return novel;
    }

    /** 包内可见是为了能单测：漏掉 {@code MONEY_UNCLEAR} 就等于让后面的号接着花钱。 */
    static boolean isGlobal(StepRunner.Kind kind) {
        return kind == StepRunner.Kind.CANCELLED
                || kind == StepRunner.Kind.NO_SERVICE
                || kind == StepRunner.Kind.NEEDS_LAUNCH
                || kind == StepRunner.Kind.CONFIG
                // 券可能已经扣了而看不出买成没买成 —— 换个号接着点等于接着花钱。
                || kind == StepRunner.Kind.MONEY_UNCLEAR;
    }

    public static String describe(Summary s) {
        if (s == null) return "订阅任务异常终止";
        StringBuilder sb = new StringBuilder();
        sb.append("成功 ").append(s.bought)
                .append("，补记已有 ").append(s.already)
                .append("，没走通 ").append(s.failed);
        if (s.spent > 0) sb.append("，共花 ").append(s.spent).append(" 代券");
        if (!s.notes.isEmpty()) sb.append('；').append(TextUtils.join("、", s.notes));
        if (s.aborted()) sb.append("（中止：").append(s.abortReason).append('）');
        return sb.toString();
    }
}
