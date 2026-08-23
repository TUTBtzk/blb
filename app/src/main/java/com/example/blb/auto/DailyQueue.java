package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Chapter;
import com.example.blb.data.CheckInDao;
import com.example.blb.data.CheckInLog;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        public int dryRun;
        public int ownedAlready;
        public int subscribeFailed;
        public int spent;
        public boolean wasDryRun;
        /** 第 3 步跑了没有；没跑的原因在 subscribeNote 里。 */
        public boolean subscribing;
        public String subscribeNote;
        public String abortReason;
        public final List<String> notes = new ArrayList<>();

        public boolean aborted() {
            return abortReason != null;
        }
    }

    /** 第 3 步的参数快照，一轮里固定不变。 */
    private static final class Plan {
        Novel novel;
        boolean dryRun;
        int maxChapters;
        int cap;
        long since;
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

        Plan plan = planSubscribe(context, subs, selectors, summary, host);
        StepRunner runner = new StepRunner(context, selectors, host);
        String ymd = Texts.todayYmd();
        int quota = Prefs.adsPerAccount(context);
        // 跳转键只在「你人在屏幕前 + 你自己开过这个开关」时才按，定时任务里恒为 false。
        AdWatchTask.Mode adMode = new AdWatchTask.Mode(
                attended && Prefs.isAdAssist(context), attended && Prefs.isAdJump(context));
        Set<Long> touched = new HashSet<>();

        for (int i = 0; i < accounts.size(); i++) {
            if (host.isCancelled()) {
                summary.abortReason = "已取消";
                break;
            }
            Account account = accounts.get(i);
            host.log("[" + (i + 1) + "/" + accounts.size() + "] " + account.displayName()
                    + "（" + account.loginKindLabel() + "）");
            try {
                AccountSwitcher.ensureLoggedIn(runner, account, accountDao,
                        accounts.size() == 1);
                Texts.Balance balance = checkInAndAds(runner, host, checkInDao, accountDao,
                        account, ymd, quota, adMode, summary);
                if (plan != null) {
                    subscribe(runner, host, subs, accountDao, plan, account, balance,
                            touched, summary);
                }
            } catch (StepRunner.StepFailure e) {
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
        return summary;
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
        else summary.checkInFailed++;
    }

    /** 决定第 3 步跑不跑。任何一个前提不满足就只跑签到和广告，不是整队失败。 */
    private static Plan planSubscribe(Context context, SubscriptionDao subs, SelectorSet selectors,
                                      Summary summary, StepRunner.Host host) {
        boolean dryRun = Prefs.isDryRun(context);
        String note = null;
        Novel novel = null;
        if (!dryRun && !Prefs.isRealBuyConfirmed(context)) {
            note = "还没在设置页确认过「允许真实购买」，这轮不订阅";
        }
        if (note == null) {
            novel = subs.targetNovel();
            if (novel == null) note = "还没设定集中订阅的目标小说，这轮只签到和看广告";
        }
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

        Plan plan = new Plan();
        plan.novel = novel;
        plan.dryRun = dryRun;
        plan.maxChapters = Prefs.maxChaptersPerRun(context);
        plan.cap = Prefs.dailySpendCap(context);
        plan.since = SubscribeQueue.startOfToday();
        summary.subscribing = true;
        summary.wasDryRun = dryRun;
        host.log((dryRun ? "订阅走干跑（不花券）" : "订阅是真实购买") + "：《" + novel.title
                + "》从第" + novel.startFrom() + "章起，本轮最多 " + plan.maxChapters + " 章"
                + (plan.cap > 0 ? "，每号每日上限 " + plan.cap + " 券" : ""));
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

        // 看完广告余额会变（签到和广告发的都是代券），重读一次才准。
        Texts.Balance balance = Texts.balance(checkIn.coupons, checkIn.vouchers);
        if (ads.watched > watchedBefore) {
            Texts.Balance after = r.readBalance(2_500);
            if (after.known()) balance = after;
        }
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

    // ---------- 第 3 步：券够就订阅 ----------

    /**
     * 用当前这个号买尽量多的章，买不动了就交给下一个号。
     *
     * <p>只挑「还没有任何号真买过」的章（{@code findUnownedChaptersFrom} 已经排除了干跑记录），
     * 所以各个号的券会摊在不同章上，合起来把可读的进度往前推，而不是几个号买同一章。
     * {@code touched} 保证同一轮里一章只试一次。
     */
    private static void subscribe(StepRunner r, StepRunner.Host host, SubscriptionDao subs,
                                  AccountDao accountDao, Plan plan, Account account,
                                  Texts.Balance balance, Set<Long> touched, Summary summary)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        int budget = balance.known() ? balance.usable() : account.usableCoupons();
        int spentToday = plan.cap > 0 ? subs.spentSince(account.id, plan.since) : 0;

        List<Chapter> chapters = subs.findUnownedChaptersFrom(
                plan.novel.id, plan.novel.startFrom(), plan.maxChapters + touched.size());
        if (chapters.isEmpty()) {
            host.log("  没有待订阅的章节（都买过了，或还没登记章节）");
            return;
        }

        for (Chapter chapter : chapters) {
            if (host.isCancelled()) {
                throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "已取消");
            }
            if (summary.bought + summary.dryRun >= plan.maxChapters) {
                host.log("  已到本轮章数上限 " + plan.maxChapters + "，不再订");
                return;
            }
            if (touched.contains(chapter.id)) continue;

            int price = chapter.priceCoupons;
            if (!plan.dryRun && price > 0) {
                if (budget > 0 && budget < price) {
                    host.log("  " + name + " 只剩 " + budget + " 券，买不起第"
                            + chapter.chapterNo + "章（需 " + price + "），换下一个号");
                    return;
                }
                if (plan.cap > 0 && spentToday + price > plan.cap) {
                    host.log("  " + name + " 今天已花 " + spentToday + " 券，再买会超上限，换下一个号");
                    return;
                }
            }

            touched.add(chapter.id);
            SubscribeTask.Result result = SubscribeTask.run(r, plan.novel, chapter, plan.dryRun);
            if (result.coupons >= 0) account.lastKnownCoupons = result.coupons;
            if (result.vouchers >= 0) account.lastKnownVouchers = result.vouchers;
            if (result.coupons >= 0 || result.vouchers >= 0) {
                accountDao.setBalance(account.id, result.coupons, result.vouchers);
                budget = account.usableCoupons();
            }

            if (!apply(subs, host, summary, account, chapter, result)) {
                // 券不够只是这个号买不起，别把这一章从整轮里划掉——后面的号可能买得起。
                if (result.status == SubscribeTask.Status.INSUFFICIENT) touched.remove(chapter.id);
                return;
            }
            if (result.status == SubscribeTask.Status.BOUGHT) {
                spentToday += result.cost > 0 ? result.cost : Math.max(0, price);
            }
        }
    }

    /** 写账本。返回 false 表示这个号别再往下买了。 */
    private static boolean apply(SubscriptionDao subs, StepRunner.Host host, Summary summary,
                                 Account account, Chapter chapter, SubscribeTask.Result result) {
        String name = account.displayName();
        String label = "第" + chapter.chapterNo + "章";
        switch (result.status) {
            case BOUGHT:
                int cost = result.cost > 0 ? result.cost : chapter.priceCoupons;
                subs.upsertPurchase(Purchase.of(account.id, chapter.id, cost, Purchase.SRC_AUTO));
                summary.bought++;
                summary.spent += cost;
                host.log("  " + name + " 订到" + label + "，花 " + cost + " 券");
                return true;
            case DRY_RUN:
                subs.upsertPurchase(Purchase.of(account.id, chapter.id, 0, Purchase.SRC_DRY_RUN));
                summary.dryRun++;
                host.log("  " + name + " " + label + " 干跑通过（只留痕，没扣券）");
                return true;
            case ALREADY:
                // 界面说已经能看了，账本却没记，补上，否则每轮都会再来一次。
                subs.upsertPurchase(Purchase.of(account.id, chapter.id,
                        chapter.priceCoupons, Purchase.SRC_AUTO));
                summary.ownedAlready++;
                host.log("  " + name + " 本来就有" + label + "，已补记账本");
                return true;
            case INSUFFICIENT:
                summary.notes.add(name + " 券不够");
                host.log("  " + name + " 券不够，换下一个号");
                return false;
            default:
                summary.subscribeFailed++;
                host.log("  " + name + " " + label + " 没走通：" + result.message);
                return false;
        }
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
            sb.append('；').append(s.wasDryRun ? "订阅干跑 " : "订阅 ").append(s.bought + s.dryRun)
                    .append(" 章");
            if (s.spent > 0) sb.append("（花 ").append(s.spent).append(" 券）");
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
