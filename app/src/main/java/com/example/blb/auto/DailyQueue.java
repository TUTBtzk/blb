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
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 每天的整套流程。按账号顺序（sort_order）把一个号的三件事全做完，再换下一个号：
 * <ol>
 *   <li>切到这个号 → 签到；</li>
 *   <li>紧接着把今天还剩的广告一个个看完 —— 默认由脚本替你按键、视频照真实时长播完，
 *       设置页关掉「替我点广告」就变成每个都停下等你自己点；</li>
 *   <li>券够就顺手订阅目标小说 —— 从你设的起始章开始，只买还没有任何号买过的章。</li>
 * </ol>
 *
 * <p>三件事塞进同一趟是有意的：签到和广告发的代券当场就能用，等整队签完再回头订阅，
 * 就得把每个号重登一遍，而重登才是最招验证码的动作。
 *
 * <p>单个账号出问题只影响它自己，队列继续。第 3 步的选择器没配好、或者没设目标小说，
 * 只关掉第 3 步，签到和广告照跑。
 */
public final class DailyQueue {

    public static final class Summary {
        public int total;
        public int checkedIn;
        public int alreadySigned;
        public int checkInFailed;
        public int skipped;
        /** 这一趟你新看完的广告数（不含今天之前已经记下的）。 */
        public int adsWatched;
        public int bought;
        public int ownedAlready;
        public int subscribeFailed;
        public int spent;
        /** 第 3 步跑了没有；没跑的原因在 subscribeNote 里。 */
        public boolean subscribing;
        public String subscribeNote;
        public String abortReason;
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
        return run(context, host, true);
    }

    /**
     * @param attended 你人在跟前吗。定时任务里是 false —— 那时候即使开着「替我点广告」也不替你点：
     *                 广告是给你看的，没人看的时候播完只是骗曝光。不看的那几个会记成没领，
     *                 等你回 App 点「跑今天的流程」再补。
     */
    public static Summary run(Context context, StepRunner.Host host, boolean attended) {
        return runOnce(context, host, attended);
    }

    private static Summary runOnce(Context context, StepRunner.Host host, boolean attended) {
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

        SubscribeRun.Plan plan = planSubscribe(context, subs, selectors, summary, host);
        SubscribeRun.Tally tally = new SubscribeRun.Tally(summary.notes);
        StepRunner runner = new StepRunner(context, selectors, host);
        String ymd = Texts.todayYmd();
        int quota = Prefs.adsPerAccount(context);
        // 跳转键只在「你人在屏幕前 + 你自己开过这个开关」时才按，定时任务里恒为 false。
        AdWatchTask.Mode adMode = new AdWatchTask.Mode(
                attended && Prefs.isAdAssist(context), attended && Prefs.isAdJump(context));
        SubscribeRun.Settled settled = new SubscribeRun.Settled();
        CheckInQueue.LoggedIn loggedIn = new CheckInQueue.LoggedIn();

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
                host.log("  今天这个号已经签完、广告也没剩，跳过（不必为它再退登重登一次）");
                CheckInQueue.refreshBalanceIfCurrent(runner, accountDao, account, host, loggedIn);
                continue;
            }
            try {
                AccountSwitcher.ensureLoggedIn(runner, account, accountDao,
                        accounts.size() == 1);
                loggedIn.nowIs(account);
                Texts.Balance balance = checkInAndAds(runner, host, checkInDao, accountDao,
                        account, ymd, quota, adMode, summary);
                if (plan != null) {
                    SubscribeRun.oneAccount(runner, host, subs, accountDao, plan, account,
                            balance, settled, tally);
                }
            } catch (StepRunner.StepFailure e) {
                if (e.kind == StepRunner.Kind.MONEY_UNCLEAR) {
                    // 订阅那一步「点了立即下载但结果不明」。签到和广告这个号明明做完了，
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
        summary.bought = tally.bought;
        summary.ownedAlready = tally.ownedAlready;
        summary.subscribeFailed = tally.failed;
        summary.spent = tally.spent;
        if (plan != null) {
            summary.stuckNote = SubscribeRun.stuckNote(subs, plan, tally);
            summary.gapNote = SubscribeRun.gapNote(subs, plan, tally);
        }
        return summary;
    }

    /**
     * 这个号今天还有事可做吗。
     *
     * <p>签到已经成功（或本来就是已签到）、广告也没剩、这一轮又不订阅 —— 那就没有任何理由
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
        return today != null && today.isSuccess() && !today.adAvailable;
    }

    /**
     * 记一条失败。今天已经签到成功的号，不把日志改成 FAILED —— 那样会把「签到成功」
     * 这件已经发生的事抹掉，下一轮还会重签。失败原因只进队列备注。
     */
    private static void noteFailure(CheckInDao dao, Account account, String ymd,
                                    String status, String message, Summary summary) {
        CheckInLog today = dao.find(account.id, ymd);
        if (today != null && today.isSuccess()) {
            summary.notes.add(account.displayName() + "：" + message);
        } else {
            CheckInQueue.record(dao, account.id, ymd, status, false, 0, -1, message);
        }
        if (CheckInLog.SKIPPED.equals(status)) summary.skipped++;
        else {
            summary.checkInFailed++;
            summary.failedNames.add(account.displayName());
        }
    }

    /** 决定第 3 步跑不跑。任何一个前提不满足就只跑签到和广告，不是整队失败。 */
    private static SubscribeRun.Plan planSubscribe(Context context, SubscriptionDao subs,
                                                   SelectorSet selectors, Summary summary,
                                                   StepRunner.Host host) {
        String note = null;
        Novel novel = SubscribeRun.resolveTarget(subs, host);
        if (novel == null) note = "还没设定集中订阅的目标小说，这轮只签到和看广告";
        if (note == null) {
            List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SUBSCRIBE);
            if (!missing.isEmpty()) {
                note = "selectors.json 缺少订阅必需的 key："
                        + TextUtils.join("、", missing) + "，这轮只签到和看广告";
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

    // ---------- 第 1、2 步：签到，紧接着广告 ----------

    /**
     * 签到 + 广告。返回这一趟读到的余额，给第 3 步判断「券够不够」用；读不到就是 -1/-1，
     * 由调用方退回账号库里记的值。
     *
     * <p>今天已经签过也照样走一遍 {@link CheckInTask}：它认出「已签到」就不会再点，
     * 顺带把我们带回签到页——广告入口和余额都在那一页上。
     */
    private static Texts.Balance checkInAndAds(StepRunner r, StepRunner.Host host,
                                               CheckInDao checkInDao, AccountDao accountDao,
                                               Account account, String ymd, int quota,
                                               AdWatchTask.Mode adMode, Summary summary)
            throws StepRunner.StepFailure {
        CheckInLog today = checkInDao.find(account.id, ymd);
        int watchedBefore = today == null ? 0 : Math.max(0, today.adsWatched);

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

        AdWatchTask.Result ads = AdWatchTask.run(r, quota, watchedBefore,
                account.displayName(), adMode);
        summary.adsWatched += Math.max(0, ads.watched - watchedBefore);
        host.log("  " + ads.message);
        if (ads.skipped) summary.notes.add(account.displayName() + " 的广告没看完");
        if (ads.promoBlocked) {
            summary.notes.add(account.displayName()
                    + " 撞上「必须点进落地页才给奖励」的广告，已放弃（要领这种得开跳转开关）");
        }

        // 余额只在「我的」页上读得到：签到面板是独立窗口，上面一个余额数字都没有。
        // 这一趟必读 —— 2026-08-23 用户报的「所有账号都无法识别有多少代券」就是因为
        // 以前只在签到面板上就地读，永远读不到。看完广告后余额会变（签到和广告发的都是代券），
        // 所以放在广告之后读。
        Texts.Balance balance = Texts.balance(checkIn.coupons, checkIn.vouchers);
        if (!balance.known() || ads.watched > watchedBefore) {
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

        boolean adPending = ads.remaining != 0 && checkIn.adAvailable;
        CheckInQueue.record(checkInDao, account.id, ymd, checkIn.status, adPending,
                ads.watched, ads.remaining, join(checkIn.message, ads.message));
        return balance;
    }

    private static String join(String a, String b) {
        if (Texts.isBlank(a)) return b;
        if (Texts.isBlank(b)) return a;
        return a + "；" + b;
    }

    public static String describe(Summary s) {
        if (s == null) return "每日流程异常终止";
        StringBuilder sb = new StringBuilder();
        sb.append("签到 ").append(s.checkedIn)
                .append("，已签 ").append(s.alreadySigned)
                .append("，失败 ").append(s.checkInFailed);
        if (s.skipped > 0) sb.append("，跳过 ").append(s.skipped);
        sb.append("；广告 ").append(s.adsWatched).append(" 个");
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
