package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Chapter;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 集中订阅队列：从目标小说里挑还没人买的章，逐章挑一个火券最多的号去订。
 *
 * <p>这里花的是真火券，所以护栏比签到严格得多：
 * <ul>
 *   <li>干跑开关默认开，且关掉它需要在设置页单独确认过一次；</li>
 *   <li>一轮最多买 {@code maxChaptersPerRun} 章；</li>
 *   <li>每个账号每天最多花 {@code dailySpendCap} 券（0 表示不限）；</li>
 *   <li>任一章出现「火券不足」或流程走不通，就换下一个号／停下，绝不重试硬刷。</li>
 * </ul>
 */
public final class SubscribeQueue {

    public static final class Summary {
        public int bought;
        public int dryRun;
        public int already;
        public int failed;
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

    public static Summary run(Context context, StepRunner.Host host) {
        Summary summary = new Summary();
        AppDatabase db = Db.get(context);
        SubscriptionDao subs = db.subscriptionDao();
        AccountDao accountDao = db.accountDao();

        boolean dryRun = Prefs.isDryRun(context);
        summary.wasDryRun = dryRun;
        if (!dryRun && !Prefs.isRealBuyConfirmed(context)) {
            // 双保险：即使 dryRun 被改成 false，没确认过就不许真花券。
            summary.abortReason = "还没在设置页确认过「允许真实购买」，本轮不跑";
            host.log(summary.abortReason);
            return summary;
        }

        Novel novel = subs.targetNovel();
        if (novel == null) {
            summary.abortReason = "还没有设定集中订阅的目标小说";
            host.log(summary.abortReason);
            return summary;
        }

        SelectorSet selectors = SelectorSet.load(context);
        host.log("选择器来自 " + selectors.source());
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SUBSCRIBE);
        missing.addAll(selectors.missing(Keys.REQUIRED_FOR_SWITCH));
        if (!missing.isEmpty()) {
            summary.abortReason = "selectors.json 缺少订阅必需的 key：" + TextUtils.join("、", missing);
            host.log(summary.abortReason);
            return summary;
        }

        int maxChapters = Prefs.maxChaptersPerRun(context);
        int cap = Prefs.dailySpendCap(context);
        host.log((dryRun ? "干跑模式" : "真实购买模式") + "，本轮最多 " + maxChapters + " 章"
                + (cap > 0 ? "，每号每日上限 " + cap + " 券" : ""));
        host.log("目标：《" + novel.title + "》");

        StepRunner runner = new StepRunner(context, selectors, host);
        List<Chapter> chapters = subs.findUnownedChapters(novel.id, maxChapters);
        if (chapters.isEmpty()) {
            host.log("没有待订阅的章节（都买过了，或还没登记章节）");
            return summary;
        }

        long since = startOfToday();
        for (int i = 0; i < chapters.size(); i++) {
            if (host.isCancelled()) {
                summary.abortReason = "已取消";
                break;
            }
            Chapter chapter = chapters.get(i);
            host.log("[" + (i + 1) + "/" + chapters.size() + "] 第" + chapter.chapterNo + "章");
            if (!handleChapter(runner, host, subs, accountDao, novel, chapter,
                    dryRun, cap, since, summary)) {
                break;
            }
        }
        return summary;
    }

    /** 返回 false 表示整队该停下。 */
    private static boolean handleChapter(StepRunner runner, StepRunner.Host host,
                                        SubscriptionDao subs, AccountDao accountDao,
                                        Novel novel, Chapter chapter, boolean dryRun,
                                        int cap, long since, Summary summary) {
        List<Account> candidates = subs.suggestBuyers(chapter.id);
        if (candidates.isEmpty()) {
            summary.failed++;
            host.log("  没有可用账号（都买过了，或都停用了）");
            return true;
        }
        // 「只启用了一个号」要看账号库的总数，不是这一章的候选数：候选少不等于没有别的号登着。
        boolean soleEnabled = accountDao.countEnabled() == 1;

        for (Account account : candidates) {
            if (host.isCancelled()) {
                summary.abortReason = "已取消";
                return false;
            }
            String name = account.displayName();

            if (!dryRun && cap > 0 && chapter.priceCoupons > 0) {
                int spent = subs.spentSince(account.id, since);
                if (spent + chapter.priceCoupons > cap) {
                    host.log("  " + name + " 今天已花 " + spent + " 券，会超上限，换下一个号");
                    continue;
                }
            }
            if (!dryRun && chapter.priceCoupons > 0 && account.lastKnownCoupons > 0
                    && account.lastKnownCoupons < chapter.priceCoupons) {
                host.log("  " + name + " 记录的火券只有 " + account.lastKnownCoupons + "，跳过");
                continue;
            }

            try {
                AccountSwitcher.ensureLoggedIn(runner, account, accountDao, soleEnabled);
                SubscribeTask.Result result =
                        SubscribeTask.run(runner, novel, chapter, dryRun);
                if (result.coupons >= 0) {
                    account.lastKnownCoupons = result.coupons;
                    accountDao.setCoupons(account.id, result.coupons);
                }
                if (apply(subs, host, summary, account, chapter, result)) return true;
                // INSUFFICIENT / FAILED：这一章换下一个号再试。
            } catch (StepRunner.StepFailure e) {
                host.log("  " + name + "：" + e.getMessage());
                if (isGlobal(e.kind)) {
                    summary.abortReason = e.getMessage();
                    return false;
                }
                summary.failed++;
                return true; // 这一章放过，别拿同一章反复折腾好几个号
            } catch (Exception e) {
                summary.failed++;
                host.log("  意外错误：" + e);
                return true;
            }
        }
        host.log("  这一章没有账号能买（上限或余额挡住了）");
        return true;
    }

    /** 写账本。返回 true 表示这一章已经有结论，不必再换号。 */
    private static boolean apply(SubscriptionDao subs, StepRunner.Host host, Summary summary,
                                Account account, Chapter chapter, SubscribeTask.Result result) {
        String name = account.displayName();
        switch (result.status) {
            case BOUGHT:
                int cost = result.cost > 0 ? result.cost : chapter.priceCoupons;
                subs.upsertPurchase(Purchase.of(account.id, chapter.id, cost, Purchase.SRC_AUTO));
                summary.bought++;
                summary.spent += cost;
                host.log("  " + name + " 订阅成功，花 " + cost + " 券");
                return true;
            case DRY_RUN:
                subs.upsertPurchase(Purchase.of(account.id, chapter.id, 0, Purchase.SRC_DRY_RUN));
                summary.dryRun++;
                host.log("  " + name + " 干跑通过（只留痕，没扣券）");
                return true;
            case ALREADY:
                // 界面说已经有了，账本却没记 —— 按真实购买补上，否则每轮都会再来一次。
                subs.upsertPurchase(Purchase.of(account.id, chapter.id,
                        chapter.priceCoupons, Purchase.SRC_AUTO));
                summary.already++;
                host.log("  " + name + " 本来就有这一章，已补记账本");
                return true;
            case INSUFFICIENT:
                host.log("  " + name + " 火券不足，换下一个号");
                summary.notes.add(name + " 火券不足");
                return false;
            default:
                host.log("  " + name + " 没走通：" + result.message);
                summary.failed++;
                return true;
        }
    }

    /** 本地时区今天零点，用于每日花费上限。 */
    static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static boolean isGlobal(StepRunner.Kind kind) {
        return kind == StepRunner.Kind.CANCELLED
                || kind == StepRunner.Kind.NO_SERVICE
                || kind == StepRunner.Kind.NEEDS_LAUNCH
                || kind == StepRunner.Kind.CONFIG;
    }

    public static String describe(Summary s) {
        if (s == null) return "订阅任务异常终止";
        StringBuilder sb = new StringBuilder();
        if (s.wasDryRun) sb.append("干跑：");
        sb.append("成功 ").append(s.bought)
                .append("，干跑 ").append(s.dryRun)
                .append("，已有 ").append(s.already)
                .append("，失败 ").append(s.failed);
        if (s.spent > 0) sb.append("，共花 ").append(s.spent).append(" 券");
        if (!s.notes.isEmpty()) sb.append('；').append(TextUtils.join("、", s.notes));
        if (s.aborted()) sb.append("（中止：").append(s.abortReason).append('）');
        return sb.toString();
    }
}
