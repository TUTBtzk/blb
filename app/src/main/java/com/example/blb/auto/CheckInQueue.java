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
        /**
         * 没签成的账号名（不含「已跳过」——那是我们自己决定不跑的，不该点名）。
         *
         * <p>为什么要名单而不只是个数：跑完那个弹窗是使用者唯一读得到的结果，而他手指动不了、
         * 打不开「今日状态」那一屏去看是哪个号。「失败 2」对他等于什么都没说。
         */
        public final List<String> failedNames = new ArrayList<>();
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
        LoggedIn loggedIn = new LoggedIn();

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
                refreshBalanceIfCurrent(runner, accountDao, account, host, loggedIn);
                continue;
            }

            try {
                AccountSwitcher.ensureLoggedIn(runner, account, accountDao,
                        accounts.size() == 1);
                loggedIn.nowIs(account);
                CheckInTask.Result result = CheckInTask.run(runner);
                // 签到面板上读不到余额（独立窗口，上面没有余额数字），到「我的」页补读一次，
                // 否则账号页永远显示不出火券／代券。
                if (result.coupons < 0 && result.vouchers < 0) {
                    Texts.Balance mine = runner.readBalanceFromMine();
                    result.coupons = mine.fire;
                    result.vouchers = mine.voucher;
                    host.log("  " + mine.describe());
                }
                record(checkInDao, account.id, ymd, result.status, result.message);
                applyAccountUpdates(accountDao, account, result);

                if (CheckInLog.OK.equals(result.status)) {
                    summary.ok++;
                    host.log("  签到成功");
                } else if (CheckInLog.ALREADY.equals(result.status)) {
                    summary.already++;
                    host.log("  已是已签到状态");
                } else {
                    summary.failed++;
                    summary.failedNames.add(name);
                    host.log("  " + result.message);
                }
            } catch (StepRunner.StepFailure e) {
                String status = statusFor(e.kind);
                record(checkInDao, account.id, ymd, status, e.getMessage());
                if (CheckInLog.SKIPPED.equals(status)) summary.skipped++;
                else {
                    summary.failed++;
                    summary.failedNames.add(name);
                }
                host.log("  " + e.getMessage());

                if (isGlobal(e.kind)) {
                    summary.abortReason = e.getMessage();
                    break;
                }
            } catch (Exception e) {
                summary.failed++;
                summary.failedNames.add(name);
                record(checkInDao, account.id, ymd, CheckInLog.FAILED, String.valueOf(e));
                host.log("  意外错误：" + e);
            }
        }
        return summary;
    }

    /**
     * 「现在登着谁」的缓存。跳过的号也想在界面上有个余额数字，但不能每跳过一个就跑一趟
     * 「我的」页 —— 登录状态只在真的切号时才变，所以整趟只问一次就够。
     */
    static final class LoggedIn {
        boolean known;
        String nickname;

        /** 刚刚确认过登的是谁（{@code AccountSwitcher.ensureLoggedIn} 回来之后）。 */
        void nowIs(Account account) {
            known = true;
            nickname = Texts.isBlank(account.nickname) ? null : account.nickname.trim();
        }
    }

    /**
     * 给「今天已经签完、这一趟被跳过」的号补一次余额 —— 但<b>只在它正好就是现在登着的
     * 那个号</b>时才读。绝不为了读一个数字去切号：切号＝退登重登，是最招验证码的动作。
     *
     * <p>所以第一次跑完之后，界面上有余额的只会是最后登着的那个号；其余的号要等下一趟
     * 轮到它们真的签到时才填上。这是有意的取舍。
     */
    static void refreshBalanceIfCurrent(StepRunner r, AccountDao dao, Account account,
                                        StepRunner.Host host, LoggedIn loggedIn) {
        if (Texts.isBlank(account.nickname)) return;
        try {
            if (!loggedIn.known) {
                loggedIn.nickname = r.readNicknameFromMine();
                loggedIn.known = true;
            }
            if (loggedIn.nickname == null
                    || !loggedIn.nickname.equals(account.nickname.trim())) {
                return;
            }
            Texts.Balance balance = r.readBalanceFromMine();
            if (!balance.known()) return;
            if (balance.fire >= 0) account.lastKnownCoupons = balance.fire;
            if (balance.voucher >= 0) account.lastKnownVouchers = balance.voucher;
            dao.setBalance(account.id, balance.fire, balance.voucher);
            host.log("  它就是现在登着的号，顺手更新余额：" + balance.describe());
        } catch (StepRunner.StepFailure e) {
            // 读余额是顺手的事，读不到（包括这一刻被取消）都不该影响这一趟的结论。
            if (e.kind != StepRunner.Kind.CANCELLED) host.log("  没读到它的余额：" + e.getMessage());
        }
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

    static void record(CheckInDao dao, long accountId, String ymd, String status,
                       String message) {
        CheckInLog log = new CheckInLog();
        log.accountId = accountId;
        log.dateYmd = ymd;
        log.status = status;
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
                || kind == StepRunner.Kind.CONFIG
                // 订阅时点过「立即下载」但结果不明：券可能已经扣了，剩下的号连试都不许试。
                || kind == StepRunner.Kind.MONEY_UNCLEAR;
    }
}
