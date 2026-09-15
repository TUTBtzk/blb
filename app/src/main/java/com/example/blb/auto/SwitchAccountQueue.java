package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Db;
import com.example.blb.util.Texts;

import java.util.List;

/**
 * 让菠萝包登录指定的账号，核实身份后结束。
 *
 * <p>用在「章节列表点一下某一章 → 换到买过这一章的号」上：账本记得第48章是谁买的，
 * 想看那一章就得登那个号，而退登＋登回来这十几步全是屏幕操作 —— 使用者手指动不了，
 * 只能由脚本替他走完。
 *
 * <p>切号本身是最招验证码的动作，所以这里一步都不多做：
 * {@link AccountSwitcher#ensureLoggedIn} 发现现在登着的就是它，就什么都不做直接回来。
 * 收尾顺手读一次余额 —— 我们本来就已经站在这个号的「我的」页上，读它不用再切号
 * （绝不为了读一个数字去切号）。
 */
public final class SwitchAccountQueue {

    public static final class Result {
        public final boolean success;
        public final String message;

        public Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private SwitchAccountQueue() {
    }

    /** @return 切号是否成功，以及通知和状态栏显示的说明。 */
    public static Result run(Context context, StepRunner.Host host, long accountId) {
        AccountDao dao = Db.get(context).accountDao();
        Account account = accountId > 0 ? dao.byId(accountId) : null;
        if (account == null) {
            return result(host, false, "账本里那条记录指着 id=" + accountId
                    + " 这个号，可账号页已经没有它了，什么都没做");
        }

        SelectorSet selectors = SelectorSet.load(context);
        host.log("选择器来自 " + selectors.source());
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SWITCH);
        missing.addAll(selectors.missing(new String[]{Keys.MINE_TAB}));
        if (!missing.isEmpty()) {
            return result(host, false, "selectors.json 缺少切号必需的 key：" + TextUtils.join("、", missing));
        }

        StepRunner runner = new StepRunner(context, selectors, host);
        host.log("要切到「" + account.displayName() + "」（" + account.loginKindLabel() + "）");
        try {
            AccountSwitcher.ensureLoggedIn(runner, account, dao);
        } catch (StepRunner.StepFailure e) {
            return result(host, false, "没切成：" + e.getMessage());
        } catch (Exception e) {
            return result(host, false, "没切成，意外错误：" + e);
        }

        String done = "现在登着的是「" + account.displayName() + "」";
        try {
            Texts.Balance balance = runner.readBalanceFromMine();
            host.log("  " + balance.describe());
            if (balance.known()) {
                if (balance.fire >= 0) account.lastKnownCoupons = balance.fire;
                if (balance.voucher >= 0) account.lastKnownVouchers = balance.voucher;
                dao.setBalance(account.id, balance.fire, balance.voucher);
                done += "，" + balance.describe();
            }
        } catch (StepRunner.StepFailure e) {
            // 余额是顺手读的，读不到不影响「已经切过去了」这个结论。
            if (e.kind != StepRunner.Kind.CANCELLED) host.log("  没读到它的余额：" + e.getMessage());
        }
        return result(host, true, done);
    }

    private static Result result(StepRunner.Host host, boolean success, String text) {
        host.log(text);
        return new Result(success, text);
    }
}
