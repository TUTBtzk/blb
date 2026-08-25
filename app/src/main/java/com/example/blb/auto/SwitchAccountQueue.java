package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Db;
import com.example.blb.util.Texts;

import java.util.List;

/**
 * 只做一件事：让菠萝包登着指定的那个账号。不签到、不看广告、不买章。
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

    private SwitchAccountQueue() {
    }

    /** @return 一句给通知和状态栏看的结论。 */
    public static String run(Context context, StepRunner.Host host, long accountId) {
        AccountDao dao = Db.get(context).accountDao();
        Account account = accountId > 0 ? dao.byId(accountId) : null;
        if (account == null) {
            return log(host, "账本里那条记录指着 id=" + accountId
                    + " 这个号，可账号页已经没有它了，什么都没做");
        }

        SelectorSet selectors = SelectorSet.load(context);
        host.log("选择器来自 " + selectors.source());
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SWITCH);
        missing.addAll(selectors.missing(new String[]{Keys.MINE_TAB}));
        if (!missing.isEmpty()) {
            return log(host, "selectors.json 缺少切号必需的 key：" + TextUtils.join("、", missing));
        }

        StepRunner runner = new StepRunner(context, selectors, host);
        host.log("要切到「" + account.displayName() + "」（" + account.loginKindLabel() + "）");
        try {
            // soleEnabled 只有在「启用的号就它一个」时才成立：那种情况下现在登着的只可能是它,
            // 昵称没记过也能顺手记下来。别的情况传 true 会把别人的昵称记到它名下。
            AccountSwitcher.ensureLoggedIn(runner, account, dao,
                    account.enabled && dao.countEnabled() == 1);
        } catch (StepRunner.StepFailure e) {
            return log(host, "没切成：" + e.getMessage());
        } catch (Exception e) {
            return log(host, "没切成，意外错误：" + e);
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
        return log(host, done);
    }

    private static String log(StepRunner.Host host, String text) {
        host.log(text);
        return text;
    }
}
