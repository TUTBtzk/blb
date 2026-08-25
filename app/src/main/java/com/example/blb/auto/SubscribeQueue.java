package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 订阅页那颗「开始自动订阅」：只做订阅，不签到、不看广告。
 *
 * <p>按账号顺序走，一个号在场时把它能买的章一次买完，代券花光了才换下一个 ——
 * 而不是按章换号。换号意味着退登重登，重登是最招验证码的动作，所以每个号只登一次。
 * 每个号具体做什么在 {@link SubscribeRun#oneAccount} 里，和签到队列的第 3 步是同一份代码。
 *
 * <p>花钱的护栏：
 * <ul>
 *   <li>干跑开关默认开，关掉它还需要在设置页单独确认过一次；</li>
 *   <li>判据是章节页那行「实付」里没有火券（用户不充值火券），读不到一律当买不起；</li>
 *   <li>每个账号每天最多花 {@code dailySpendCap} 代券（0 表示不限）；</li>
 *   <li>章数上限只是选择器错位时的安全阀，正常的停止条件是「代券不够 → 换号」。</li>
 * </ul>
 */
public final class SubscribeQueue {

    public static final class Summary {
        public int total;
        public int bought;
        public int dryRun;
        public int already;
        public int failed;
        /** 实付代券累计。 */
        public int spent;
        public boolean wasDryRun;
        public String abortReason;
        public final List<String> notes = new ArrayList<>();

        public boolean aborted() {
            return abortReason != null;
        }
    }

    private SubscribeQueue() {
    }

    /**
     * 跑一趟订阅。
     *
     * <p>外面这一层只做一件事：<b>无论从哪条路退出去，都把干跑拨回来</b>
     * （{@link SubscribeRun#restoreDryRunAfterRealBuy}）。真买是一次性授权，而用户关不掉
     * 那颗开关 —— 「没有启用的账号」「selectors 缺 key」这种跑之前就中止的路，
     * 以前会让开关一直开着，下一趟定时任务就会接着花他的代券。
     */
    public static Summary run(Context context, StepRunner.Host host) {
        try {
            return runOnce(context, host);
        } finally {
            SubscribeRun.restoreDryRunAfterRealBuy(context, host);
        }
    }

    private static Summary runOnce(Context context, StepRunner.Host host) {
        Summary summary = new Summary();
        AppDatabase db = Db.get(context);
        SubscriptionDao subs = db.subscriptionDao();
        AccountDao accountDao = db.accountDao();

        Novel novel = prepare(context, subs, summary, host);
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
        summary.wasDryRun = plan.dryRun;
        host.log(plan.describe());
        SubscribeRun.clearOldDryRuns(subs, plan, host);

        SubscribeRun.Tally tally = new SubscribeRun.Tally(summary.notes);
        StepRunner runner = new StepRunner(context, selectors, host);
        Set<Long> touched = new HashSet<>();

        for (int i = 0; i < accounts.size(); i++) {
            if (host.isCancelled()) {
                summary.abortReason = "已取消";
                break;
            }
            if (plan.reachedBuyLimit(tally.bought)) {
                // 保险丝：真买那一趟只准买这么多章，剩下的号连登录都不做。
                host.log("真买上限到了（" + plan.buyLimit + " 章），后面 "
                        + (accounts.size() - i) + " 个号这一趟不动");
                break;
            }
            Account account = accounts.get(i);
            host.log("[" + (i + 1) + "/" + accounts.size() + "] " + account.displayName()
                    + "（" + account.loginKindLabel() + "）");
            try {
                AccountSwitcher.ensureLoggedIn(runner, account, accountDao, accounts.size() == 1);
                // 余额得从「我的」页读，签到面板上一个数字都没有；读不到就退回账号库里记的代券。
                Texts.Balance balance = runner.readBalanceFromMine();
                host.log("  " + balance.describe());
                if (balance.known()) {
                    if (balance.fire >= 0) account.lastKnownCoupons = balance.fire;
                    if (balance.voucher >= 0) account.lastKnownVouchers = balance.voucher;
                    accountDao.setBalance(account.id, balance.fire, balance.voucher);
                }
                SubscribeRun.oneAccount(runner, host, subs, accountDao, plan, account,
                        balance, touched, tally);
            } catch (StepRunner.StepFailure e) {
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
        summary.bought = tally.bought;
        summary.dryRun = tally.dryRun;
        summary.already = tally.ownedAlready;
        summary.failed = tally.failed;
        summary.spent = tally.spent;
        return summary;
    }

    /** 跑之前必须成立的几件事。返回 null = 这一轮不跑，原因已经写进 summary。 */
    private static Novel prepare(Context context, SubscriptionDao subs, Summary summary,
                                 StepRunner.Host host) {
        boolean dryRun = Prefs.isDryRun(context);
        summary.wasDryRun = dryRun;
        if (!dryRun && !Prefs.isRealBuyConfirmed(context)) {
            // 双保险：即使 dryRun 被改成 false，没确认过就不许真花券。
            summary.abortReason = "还没在设置页确认过「允许真实购买」，本轮不跑";
            host.log(summary.abortReason);
            return null;
        }
        Novel novel = SubscribeRun.resolveTarget(subs, host);
        if (novel == null) {
            summary.abortReason = "还没有设定集中订阅的目标小说";
            host.log(summary.abortReason);
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
        if (s.wasDryRun) sb.append("干跑：");
        sb.append("成功 ").append(s.bought)
                .append("，干跑 ").append(s.dryRun)
                .append("，补记已有 ").append(s.already)
                .append("，没走通 ").append(s.failed);
        if (s.spent > 0) sb.append("，共花 ").append(s.spent).append(" 代券");
        if (!s.notes.isEmpty()) sb.append('；').append(TextUtils.join("、", s.notes));
        if (s.aborted()) sb.append("（中止：").append(s.abortReason).append('）');
        return sb.toString();
    }
}
