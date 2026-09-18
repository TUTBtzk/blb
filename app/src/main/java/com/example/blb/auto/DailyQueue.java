package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.CheckInDao;
import com.example.blb.data.CheckInLog;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.ui.LedgerEdits;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 每天的整套流程。按账号顺序（sort_order）完成切号 → 签到 → 订阅，再换下一个号：
 * <ol>
 *   <li>切到这个号 → 签到；</li>
 *   <li>券够就顺手订阅目标小说 —— 从你设的起始章开始，只买还没有任何号买过的章。</li>
 * </ol>
 *
 * <p>2026-09-14 真机反馈要求只保留签到页的整套入口；同一个号签完立刻读余额并订阅，
 * 才能当场使用签到所得代券，避免整队签完后再逐号重登。
 *
 * <p>单个账号出问题只影响它自己，队列继续；金额不明仍整趟停止。
 * 没设目标小说或订阅选择器未配齐时只签到，已有目标却没有目录时仍整趟停止。
 */
public final class DailyQueue {

    public static final class Summary {
        public int total;
        public int checkedIn;
        public int alreadySigned;
        public int checkInFailed;
        public int skipped;
        public int bought;
        public int ownedAlready;
        public int subscribeFailed;
        public int spent;
        /** 订阅跑了没有；没跑的原因在 subscribeNote 里。 */
        public boolean subscribing;
        public String subscribeNote;
        public String abortReason;
        public String catalogNote;
        public String nextChapterNote;
        /**
         * 整本现在<b>停在第几章</b>（{@link SubscribeRun#stuckNote}）；没卡住就是 null。
         *
         * <p>队列绝不越过一章：一章订不下来就换下一个号试同一章，8 个号都不行就停在那里。
         * 所以这句话是「不漏订、8 个号拼出完整一本」在弹窗上唯一看得见的地方 ——
         * 使用者手指动不了、点不进运行日志，「没走通 1」对他等于什么都没说。
         *
         * <p>它<b>不算失败</b>：8 个号都只是代券不够也会有这一句。真出错由
         * 「订阅没走通 N 个号」那一行说。
         */
        public String stuckNote;
        /**
         * 这一趟有没有<b>越过</b>一章（{@link SubscribeRun#gapNote}）；没有缺口就是 null。
         *
         * <p>正常永远是 null —— 留着当不变量断言，一旦有值就说明「不跳过任何一章」被破坏了。
         */
        public String gapNote;
        public final List<String> notes = new ArrayList<>();
        /**
         * 没签成的账号名（不含「已跳过」）。跑完那个弹窗要靠它点名 ——
         * 使用者手指动不了，打不开「今日状态」去看是哪个号，「失败 2」对他等于什么都没说。
         */
        public final List<String> failedNames = new ArrayList<>();

        public boolean aborted() {
            return abortReason != null;
        }
    }

    private DailyQueue() {
    }

    public static Summary run(Context context, StepRunner.Host host) {
        AppDatabase db = Db.get(context);
        AccountDao accountDao = db.accountDao();
        CheckInDao checkInDao = db.checkInDao();
        SubscriptionDao subs = db.subscriptionDao();

        Summary summary = new Summary();
        List<Account> accounts = accountDao.loadEnabled();
        summary.total = accounts.size();
        if (accounts.isEmpty()) {
            host.log("没有启用的账号，什么都没做");
            return summary;
        }

        SelectorSet selectors = SelectorSet.load(context);
        host.log("选择器来自 " + selectors.source());
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_CHECKIN);
        if (!missing.isEmpty()) {
            summary.abortReason = "selectors.json 缺少签到必需的 key：" + TextUtils.join("、", missing);
            host.log(summary.abortReason);
            return summary;
        }

        SubscribeRun.Plan plan;
        try {
            plan = planSubscribe(context, subs, selectors, summary, host);
        } catch (StepRunner.StepFailure failure) {
            summary.abortReason = failure.getMessage();
            summary.subscribeNote = failure.getMessage();
            host.log(summary.abortReason);
            return summary;
        } catch (RuntimeException failure) {
            summary.abortReason = "订阅账本预检失败，整趟停止：" + failure.getMessage();
            host.log(summary.abortReason);
            return summary;
        }
        SubscribeRun.Tally tally = new SubscribeRun.Tally(summary.notes);
        StepRunner runner = new StepRunner(context, selectors, host);
        String ymd = Texts.todayYmd();
        SubscribeRun.Settled settled = new SubscribeRun.Settled();
        CheckInQueue.LoggedIn loggedIn = new CheckInQueue.LoggedIn();

        try {
            for (int i = 0; i < accounts.size(); i++) {
                if (host.isCancelled()) {
                    summary.abortReason = "已取消";
                    break;
                }
                Account account = accounts.get(i);
                host.log("[" + (i + 1) + "/" + accounts.size() + "] " + account.displayName()
                        + "（" + account.loginKindLabel() + "）");
                if (nothingLeftToday(checkInDao, account, ymd, plan)) {
                    summary.alreadySigned++;
                    host.log("  今天这个号已经签完，这一趟没有订阅任务，跳过（不必再退登重登一次）");
                    CheckInQueue.refreshBalanceIfCurrent(runner, accountDao, account, host, loggedIn);
                    continue;
                }
                try {
                    AccountSwitcher.ensureLoggedIn(runner, account, accountDao,
                            accounts.size() == 1);
                    loggedIn.nowIs(account);
                    Texts.Balance balance = checkInAndReadBalance(runner, host, checkInDao,
                            accountDao, account, ymd, summary);
                    if (plan != null) {
                        plan = CatalogQueue.syncIfExpired(runner, host, subs, plan, account, i);
                        summary.catalogNote = CatalogStatus.runNote(plan.novel,
                                System.currentTimeMillis(), Prefs.catalogMaxAgeHours(context));
                        SubscribeRun.oneAccount(runner, host, subs, accountDao, plan, account,
                                settled, tally);
                    }
                } catch (StepRunner.StepFailure e) {
                    if (CheckInQueue.isGlobal(e.kind)) summary.abortReason = e.getMessage();
                    if (e.kind == StepRunner.Kind.MONEY_UNCLEAR) {
                        // 订阅那一步「点了立即下载但结果不明」。这个号的签到已经做完了，
                        // 不能把它记成签到失败（那会让今天再退登重登一次去签一遍）。整趟停下。
                        host.log("  " + e.getMessage());
                        summary.abortReason = e.getMessage();
                        break;
                    }
                    noteFailure(checkInDao, account, ymd, CheckInQueue.statusFor(e.kind),
                            e.getMessage(), summary);
                    host.log("  " + e.getMessage());
                    if (CheckInQueue.isGlobal(e.kind)) {
                        summary.abortReason = e.getMessage();
                        break;
                    }
                } catch (Exception e) {
                    noteFailure(checkInDao, account, ymd, CheckInLog.FAILED, String.valueOf(e), summary);
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
            summary.ownedAlready = tally.ownedAlready;
            summary.subscribeFailed = tally.failed;
            summary.spent = tally.spent;
        }
        if (plan != null) {
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
        }
        return summary;
    }

    /**
     * 这个号今天还有事可做吗。
     *
     * <p>签到已经成功（或本来就是已签到）、这一轮又不订阅 —— 那就没有任何理由
     * 再切到它：切号意味着退登重登，而重登是最招验证码的动作。
     *
     * <p>这条规则还顺手把「被系统杀掉之后自动接着跑」变得便宜又安全：MIUI 会把整个进程杀掉
     * （SwipeUpClean），系统随后把原来那条启动请求重发一遍，队列于是从第 1 个号重新开始。
     * 有了这一跳，重来的那趟会直接走到上次断掉的地方，而不是把前面几个号全部重登一遍。
     */
    private static boolean nothingLeftToday(CheckInDao dao, Account account, String ymd,
                                            SubscribeRun.Plan plan) {
        if (plan != null) return false;
        CheckInLog today = dao.find(account.id, ymd);
        return today != null && today.isSuccess();
    }

    /**
     * 记一条失败。今天已经签到成功的号，不把日志改成 FAILED —— 那样会把「签到成功」
     * 这件已经发生的事抹掉，下一轮还会重签。失败原因只进队列备注。
     */
    private static void noteFailure(CheckInDao dao, Account account, String ymd,
                                    String status, String message, Summary summary) {
        try {
            CheckInLog today = dao.find(account.id, ymd);
            if (today != null && today.isSuccess()) {
                summary.notes.add(account.displayName() + "：" + message);
            } else {
                CheckInQueue.record(dao, account.id, ymd, status, message);
            }
            if (CheckInLog.SKIPPED.equals(status)) summary.skipped++;
            else {
                summary.checkInFailed++;
                summary.failedNames.add(account.displayName());
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException(account.displayName() + "：" + message
                    + "；失败状态未能保存：" + failure.getMessage(), failure);
        }
    }

    /** 没有目标仍可只签到；已有目标却没目录必须整队停，不能再把空表说成订阅已完成。 */
    private static SubscribeRun.Plan planSubscribe(Context context, SubscriptionDao subs,
                                                   SelectorSet selectors, Summary summary,
                                                   StepRunner.Host host) throws StepRunner.StepFailure {
        String note = null;
        Novel novel = LedgerEdits.duringRun(host, () -> SubscribeRun.resolveTarget(subs, host));
        if (novel == null) note = "还没设定集中订阅的目标小说，这轮只签到";
        if (note == null) {
            summary.catalogNote = CatalogStatus.runNote(novel,
                    System.currentTimeMillis(), Prefs.catalogMaxAgeHours(context));
            summary.nextChapterNote = SubscribeRun.currentNextChapterNote(subs, novel);
            host.log(summary.catalogNote);
            SubscribeRun.requireCatalog(subs, novel);
            List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SUBSCRIBE);
            if (!missing.isEmpty()) {
                note = "selectors.json 缺少订阅必需的 key："
                        + TextUtils.join("、", missing) + "，这轮只签到";
            }
        }
        if (note != null) {
            summary.subscribeNote = note;
            host.log(note);
            return null;
        }

        SubscribeRun.Plan plan = SubscribeRun.Plan.from(context, novel);
        summary.subscribing = true;
        host.log(plan.describe());
        return plan;
    }

    // ---------- 签到后读取余额 ----------

    /**
     * 返回这一趟签到后读到的余额，给订阅判断「券够不够」用；读不到就是 -1/-1。
     *
     * <p>今天已经签过也照样走一遍 {@link CheckInTask}：它认出「已签到」就不会再点，
     * 因为当天本地记录不能代替本次服务器界面的签到状态。
     */
    private static Texts.Balance checkInAndReadBalance(StepRunner r, StepRunner.Host host,
                                                      CheckInDao checkInDao, AccountDao accountDao,
                                                      Account account, String ymd, Summary summary)
            throws StepRunner.StepFailure {
        CheckInTask.Result checkIn = CheckInTask.run(r);
        if (CheckInLog.OK.equals(checkIn.status)) {
            summary.checkedIn++;
            host.log("  签到成功");
        } else if (CheckInLog.ALREADY.equals(checkIn.status)) {
            summary.alreadySigned++;
            host.log("  今天已经是已签到状态");
        } else {
            summary.checkInFailed++;
            summary.failedNames.add(account.displayName());
            host.log("  签到没走通：" + checkIn.message);
        }

        // 余额只在「我的」页上读得到：签到面板是独立窗口，上面一个余额数字都没有。
        // 这一趟必读 —— 2026-08-23 用户报的「所有账号都无法识别有多少代券」就是因为
        // 以前只在签到面板上就地读，永远读不到。签到发的代券可能改变余额，因此在签到之后读。
        Texts.Balance balance = Texts.balance(checkIn.coupons, checkIn.vouchers);
        if (!balance.known()) {
            Texts.Balance mine = r.readBalanceFromMine();
            if (mine.known()) balance = mine;
        }
        host.log("  " + balance.describe());
        if (balance.known()) {
            if (balance.fire >= 0) account.lastKnownCoupons = balance.fire;
            if (balance.voucher >= 0) account.lastKnownVouchers = balance.voucher;
            accountDao.setBalance(account.id, balance.fire, balance.voucher);
        }
        if (CheckInLog.OK.equals(checkIn.status) || CheckInLog.ALREADY.equals(checkIn.status)) {
            accountDao.setLastCheckInAt(account.id, System.currentTimeMillis());
        }

        CheckInQueue.record(checkInDao, account.id, ymd, checkIn.status, checkIn.message);
        return balance;
    }

    public static String describe(Summary s) {
        if (s == null) return "每日流程异常终止";
        StringBuilder sb = new StringBuilder();
        sb.append("签到 ").append(s.checkedIn)
                .append("，已签 ").append(s.alreadySigned)
                .append("，失败 ").append(s.checkInFailed);
        if (s.skipped > 0) sb.append("，跳过 ").append(s.skipped);
        if (s.subscribing) {
            sb.append("；订阅 ").append(s.bought).append(" 章");
            if (s.spent > 0) sb.append("（花 ").append(s.spent).append(" 代券）");
            if (s.ownedAlready > 0) sb.append("，补记 ").append(s.ownedAlready);
            if (s.subscribeFailed > 0) sb.append("，没走通 ").append(s.subscribeFailed);
        } else if (s.subscribeNote != null) {
            sb.append('；').append(s.subscribeNote);
        }
        if (!s.notes.isEmpty()) sb.append('；').append(TextUtils.join("、", s.notes));
        if (s.aborted()) sb.append("（中止：").append(s.abortReason).append('）');
        return sb.toString();
    }
}
