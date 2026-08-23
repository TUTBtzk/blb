package com.example.blb.auto;

import android.content.Context;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.CheckInDao;
import com.example.blb.data.CheckInLog;
import com.example.blb.data.Db;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 依次为所有启用的账号跑签到。单个账号失败只影响它自己，队列继续；
 * 只有「无障碍没开 / 拉不起菠萝包 / 选择器没配 / 用户中止」这类全局问题才整队停下。
 */
public final class CheckInQueue {

    public static final class Summary {
        public int total;
        public int ok;
        public int already;
        public int failed;
        public int skipped;
        /** 签到页有广告奖励待手动领取的账号名。 */
        public final List<String> adPending = new ArrayList<>();
        public String abortReason;

        public boolean aborted() {
            return abortReason != null;
        }
    }

    private CheckInQueue() {
    }

    public static Summary run(Context context, StepRunner.Host host) {
        AppDatabase db = Db.get(context);
        AccountDao accountDao = db.accountDao();
        CheckInDao checkInDao = db.checkInDao();

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
            summary.abortReason = "selectors.json 缺少签到必需的 key：" + android.text.TextUtils.join("、", missing);
            host.log(summary.abortReason);
            return summary;
        }

        StepRunner runner = new StepRunner(context, selectors, host);
        String ymd = Texts.todayYmd();

        for (int i = 0; i < accounts.size(); i++) {
            if (host.isCancelled()) {
                summary.abortReason = "已取消";
                break;
            }
            Account account = accounts.get(i);
            String name = account.displayName();
            host.log("[" + (i + 1) + "/" + accounts.size() + "] " + name);

            CheckInLog existing = checkInDao.find(account.id, ymd);
            if (existing != null && existing.isSuccess()) {
                summary.already++;
                host.log("  今天已经签过，跳过");
                if (existing.adAvailable) summary.adPending.add(name);
                continue;
            }

            try {
                AccountSwitcher.ensureLoggedIn(runner, account, accountDao,
                        accounts.size() == 1);
                CheckInTask.Result result = CheckInTask.run(runner);
                record(checkInDao, account.id, ymd, result.status, result.adAvailable,
                        0, -1, result.message);
                applyAccountUpdates(accountDao, account, result);

                if (CheckInLog.OK.equals(result.status)) {
                    summary.ok++;
                    host.log("  签到成功");
                } else if (CheckInLog.ALREADY.equals(result.status)) {
                    summary.already++;
                    host.log("  已是已签到状态");
                } else {
                    summary.failed++;
                    host.log("  " + result.message);
                }
                if (result.adAvailable) {
                    summary.adPending.add(name);
                    host.log("  签到页还有广告奖励没领（这一趟只签到，广告在「今天的流程」里跑）");
                }
            } catch (StepRunner.StepFailure e) {
                String status = statusFor(e.kind);
                record(checkInDao, account.id, ymd, status, false, 0, -1, e.getMessage());
                if (CheckInLog.SKIPPED.equals(status)) summary.skipped++;
                else summary.failed++;
                host.log("  " + e.getMessage());

                if (isGlobal(e.kind)) {
                    summary.abortReason = e.getMessage();
                    break;
                }
            } catch (Exception e) {
                summary.failed++;
                record(checkInDao, account.id, ymd, CheckInLog.FAILED, false, 0, -1,
                        String.valueOf(e));
                host.log("  意外错误：" + e);
            }
        }
        return summary;
    }

    private static void applyAccountUpdates(AccountDao dao, Account account,
                                           CheckInTask.Result result) {
        if (result.coupons >= 0 || result.vouchers >= 0) {
            if (result.coupons >= 0) account.lastKnownCoupons = result.coupons;
            if (result.vouchers >= 0) account.lastKnownVouchers = result.vouchers;
            dao.setBalance(account.id, result.coupons, result.vouchers);
        }
        if (CheckInLog.OK.equals(result.status) || CheckInLog.ALREADY.equals(result.status)) {
            dao.setLastCheckInAt(account.id, System.currentTimeMillis());
        }
    }

    /** 写签到日志。ads 两项传 0／-1 表示这次没碰广告，沿用当天已经记下的数，不要清零。 */
    static void record(CheckInDao dao, long accountId, String ymd, String status,
                       boolean adAvailable, int adsWatched, int adsRemaining, String message) {
        CheckInLog log = new CheckInLog();
        log.accountId = accountId;
        log.dateYmd = ymd;
        log.status = status;
        log.adAvailable = adAvailable;
        log.adsWatched = Math.max(0, adsWatched);
        log.adsRemaining = adsRemaining;
        CheckInLog old = dao.find(accountId, ymd);
        if (old != null) {
            if (log.adsWatched == 0) log.adsWatched = Math.max(0, old.adsWatched);
            if (log.adsRemaining < 0) log.adsRemaining = old.adsRemaining;
        }
        log.message = message;
        log.createdAt = System.currentTimeMillis();
        dao.upsert(log);
    }

    static String statusFor(StepRunner.Kind kind) {
        switch (kind) {
            case CAPTCHA:
                return CheckInLog.BLOCKED_CAPTCHA;
            case SKIPPED:
                return CheckInLog.SKIPPED;
            default:
                return CheckInLog.FAILED;
        }
    }

    /**
     * 这些问题换个账号重试也一样，直接整队停下省时间。
     *
     * <p>{@code NEEDS_LAUNCH}<b>不</b>在这里面：实测有过一次残留的透明第三方落地页让
     * 「拉不起菠萝包」误报，结果第一个号就把整队掐死了。现在 {@code launchTarget} 自己会先按
     * 返回再反复重试，真拉不起来也只算这一个号的失败，让后面的号还有机会。
     */
    static boolean isGlobal(StepRunner.Kind kind) {
        return kind == StepRunner.Kind.CANCELLED
                || kind == StepRunner.Kind.NO_SERVICE
                || kind == StepRunner.Kind.CONFIG;
    }
}
