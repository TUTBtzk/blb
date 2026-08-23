package com.example.blb.auto;

import com.example.blb.crypto.KeyStoreBox;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.util.Texts;

/**
 * 保证菠萝包当前登录的就是指定账号。已经是了就什么都不做——不必要的重登最容易招来验证码。
 *
 * <p>昵称是判断「现在登的是谁」的唯一依据。登完之后昵称跟登记的对不上就直接失败，
 * 而不是接着往下跑：后面那步是花券买章，用错号买等于把券丢了。
 *
 * <p>反过来，账号页里<b>还没记昵称</b>的时候，「对不上」只说明我们不知道，不说明登错了 ——
 * 那种情况下绝不退登：只启用一个号时就把当前昵称记下来接着跑，多个号时停下来让你先填昵称。
 * 会把人退出来又登不回去（一键登录、微信这些的授权页本 App 点不了）是这里最坏的结果。
 *
 * <p>四种登录方式的自动化程度天差地别：
 * <ul>
 *   <li><b>账号密码</b>：全自动。</li>
 *   <li><b>本机号码一键登录</b>：能自动勾同意并点按钮，但号码由 SIM 卡决定，
 *       所以只能登成那一个号。</li>
 *   <li><b>微信 / QQ / 微博</b>：授权页属于对应的客户端（com.tencent.mm 等），
 *       本 App 的无障碍范围只有 com.sfacg，看不见也点不了 —— 这一步一定停下来等你点。
 *       要让它自动，就得把无障碍权限扩到微信/QQ/微博上，那等于让本 App 能读那些
 *       App 的全部界面，代价太大，不做。</li>
 * </ul>
 */
public final class AccountSwitcher {

    private static final long NAV_TIMEOUT = 10_000;
    private static final long LOGIN_TIMEOUT = 25_000;

    private AccountSwitcher() {
    }

    public static void ensureLoggedIn(StepRunner r, Account account, AccountDao dao,
                                      boolean soleEnabled) throws StepRunner.StepFailure {
        r.ensureHome(3);
        r.click(Keys.MINE_TAB, NAV_TIMEOUT);

        String current = r.readText(Keys.NICKNAME, 6_000);
        boolean known = !Texts.isBlank(account.nickname);
        if (known && !Texts.isBlank(current) && account.nickname.trim().equals(current.trim())) {
            return;
        }
        if (!known && !Texts.isBlank(current)) {
            // 这个号还没记过昵称。此时「昵称对不上」只说明我们不知道，不说明登错了号 ——
            // 拿它当理由去退登，很可能把本来就登对的号踢下线，而一键登录／微信这些还登不回来。
            if (soleEnabled) {
                // 只启用了这一个号，那现在登着的就只能是它，记下昵称接着跑。
                account.nickname = current.trim();
                if (dao != null) dao.setNickname(account.id, account.nickname);
                r.log("把当前登录的昵称「" + account.nickname + "」记到「"
                        + account.displayName() + "」名下了");
                return;
            }
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "「" + account.displayName() + "」还没记过昵称，而现在登着的是「"
                            + current.trim() + "」。我不能凭猜测断定这是不是同一个号，"
                            + "更不会为此退登——请先在账号页把昵称填上（或只启用这一个号跑一次，让我记下来）");
        }
        login(r, account, dao);
    }

    private static void login(StepRunner r, Account account, AccountDao dao)
            throws StepRunner.StepFailure {
        String kind = account.loginKind == null ? Account.KIND_PASSWORD : account.loginKind;
        if (!Account.KIND_PASSWORD.equals(kind) && !onLoginPage(r)) {
            // 退出去之后这几种方式都得你亲手点（三方授权页在别的 App 里，一键登录页也还没实测），
            // 所以退登之前先问一句 —— 别把你退出来又进不去。
            waitForYou(r, account, "接下来要退出当前登录的账号，再用" + account.loginKindLabel()
                    + "登回来 —— 那一步的授权页我点不了，需要你亲自点。"
                    + "确定就点「继续」，不想换号就点「跳过此账号」");
        }
        logoutIfNeeded(r);
        openLoginPage(r);

        switch (kind) {
            case Account.KIND_PASSWORD:
                submitPassword(r, account);
                break;
            case Account.KIND_PHONE_ONE_TAP:
                oneTap(r, account);
                break;
            default:
                thirdParty(r, account, kind);
                break;
        }
        finishLogin(r, account, dao);
    }

    // ---------- 各种登录方式 ----------

    private static void submitPassword(StepRunner r, Account account)
            throws StepRunner.StepFailure {
        if (!account.hasPassword()) {
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "账号「" + account.displayName() + "」没有保存密码，无法自动切换");
        }
        String password;
        try {
            password = KeyStoreBox.open(account.encPassword, account.encIv);
        } catch (KeyStoreBox.CryptoException e) {
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "取不出「" + account.displayName() + "」的密码：" + e.getMessage());
        }

        // 一键登录页要先「切换手机号或邮箱登录」才有输入框，再切到「密码登录」。
        if (r.findAny(Keys.LOGIN_ACCOUNT_FIELD) == null) {
            StepRunner.Outcome other = r.findAny(Keys.LOGIN_SWITCH_PHONE);
            if (other != null) r.clickNode(Keys.LOGIN_SWITCH_PHONE, other.node);
        }
        StepRunner.Outcome pwSwitch = r.findAny(Keys.LOGIN_SWITCH_TO_PASSWORD);
        if (pwSwitch != null) {
            r.clickNode(Keys.LOGIN_SWITCH_TO_PASSWORD, pwSwitch.node);
        }

        r.setText(Keys.LOGIN_ACCOUNT_FIELD, account.loginName, NAV_TIMEOUT);
        r.setText(Keys.LOGIN_PASSWORD_FIELD, password, NAV_TIMEOUT);
        agree(r);
        r.click(Keys.LOGIN_SUBMIT, NAV_TIMEOUT);
        r.guardCaptcha();
    }

    private static void oneTap(StepRunner r, Account account) throws StepRunner.StepFailure {
        agree(r);
        StepRunner.Outcome button = r.findAny(Keys.LOGIN_ONE_TAP);
        if (button == null) {
            // 一键登录页是阿里云号码认证的界面，控件还没实测过；找不到就交给你点一下。
            waitForYou(r, account, "找不到「本机号码一键登录」按钮，请手动点一下（记得先勾同意），"
                    + "登进去后点「继续」");
            return;
        }
        r.clickNode(Keys.LOGIN_ONE_TAP, button.node);
        r.guardCaptcha();
    }

    private static void thirdParty(StepRunner r, Account account, String kind)
            throws StepRunner.StepFailure {
        String key = keyFor(kind);
        String name = account.loginKindLabel();
        agree(r);
        StepRunner.Outcome icon = key == null ? null : r.findAny(key);
        if (icon != null) r.clickNode(key, icon.node);
        // 授权页在 name 那个 App 里，本 App 看不到，只能等你点完回来。
        waitForYou(r, account, "请在" + name + "里完成授权登录"
                + (icon == null ? "（图标没认出来，也需要你自己点开）" : "")
                + "，回到菠萝包后点「继续」");
    }

    private static String keyFor(String kind) {
        switch (kind) {
            case Account.KIND_WECHAT:
                return Keys.LOGIN_WECHAT;
            case Account.KIND_QQ:
                return Keys.LOGIN_QQ;
            case Account.KIND_WEIBO:
                return Keys.LOGIN_WEIBO;
            default:
                return null;
        }
    }

    /** 勾「本人已阅读并同意…」。已经勾了就别再点，点一下反而取消。 */
    private static void agree(StepRunner r) throws StepRunner.StepFailure {
        StepRunner.Outcome agree = r.findAny(Keys.LOGIN_AGREE_CHECKBOX);
        if (agree != null && !agree.node.checked()) {
            r.clickNode(Keys.LOGIN_AGREE_CHECKBOX, agree.node);
        }
    }

    private static void waitForYou(StepRunner r, Account account, String reason)
            throws StepRunner.StepFailure {
        StepRunner.Decision d = r.awaitUser(account.displayName() + "：" + reason);
        if (d == StepRunner.Decision.SKIP) {
            throw new StepRunner.StepFailure(StepRunner.Kind.SKIPPED,
                    "你跳过了「" + account.displayName() + "」的登录");
        }
        if (d == StepRunner.Decision.ABORT) {
            throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "登录时中止");
        }
    }

    // ---------- 收尾：确认登进去的确实是这个号 ----------

    private static void finishLogin(StepRunner r, Account account, AccountDao dao)
            throws StepRunner.StepFailure {
        StepRunner.Outcome result = awaitLoginResult(r);
        if (Keys.CAPTCHA_HINT.equals(result.key)) {
            r.guardCaptcha();
            result = awaitLoginResult(r);
        }
        if (Keys.LOGIN_ERROR.equals(result.key)) {
            String msg = result.node.text();
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "登录被拒：" + (Texts.isBlank(msg) ? "账号或密码有问题" : msg.trim()));
        }

        String nickname = result.node.text();
        if (Texts.isBlank(nickname)) {
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "登录后读不到昵称，没法确认现在登的是哪个号");
        }
        String trimmed = nickname.trim();
        if (!Texts.isBlank(account.nickname) && !trimmed.equals(account.nickname.trim())) {
            // 登进来的不是想登的那个号。接着跑就会拿错号去签到、花错号的券。
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "登进来的昵称是「" + trimmed + "」，但「" + account.displayName()
                            + "」登记的是「" + account.nickname.trim()
                            + "」。已停下，请核对账号页里的昵称");
        }
        if (!trimmed.equals(account.nickname) && dao != null) {
            account.nickname = trimmed;
            dao.setNickname(account.id, trimmed);
        }
    }

    /** 三方授权回来后可能停在别的页面，读不到昵称就先切一次「我的」再等。 */
    private static StepRunner.Outcome awaitLoginResult(StepRunner r) throws StepRunner.StepFailure {
        try {
            return r.waitForAny(LOGIN_TIMEOUT, Keys.NICKNAME, Keys.LOGIN_ERROR, Keys.CAPTCHA_HINT);
        } catch (StepRunner.StepFailure e) {
            if (e.kind != StepRunner.Kind.TIMEOUT) throw e;
            r.click(Keys.MINE_TAB, NAV_TIMEOUT);
            return r.waitForAny(LOGIN_TIMEOUT, Keys.NICKNAME, Keys.LOGIN_ERROR, Keys.CAPTCHA_HINT);
        }
    }

    // ---------- 退登与进入登录页 ----------

    /** 当前有人登录才需要退出；已经在未登录态直接进登录表单。 */
    private static void logoutIfNeeded(StepRunner r) throws StepRunner.StepFailure {
        if (onLoginPage(r)) {
            return;
        }
        // 「设置」在「我的」页最底部，「退出登录」在设置页最底部，两处都得先往下翻。
        StepRunner.Outcome settings = r.findAny(Keys.SETTINGS_ENTRY);
        if (settings == null) {
            r.click(Keys.MINE_TAB, NAV_TIMEOUT);
            settings = r.scrollToKey(Keys.SETTINGS_ENTRY, 8);
        }
        if (settings == null) {
            throw new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT,
                    "找不到「设置」入口，退不出当前账号");
        }
        r.clickNode(Keys.SETTINGS_ENTRY, settings.node);

        StepRunner.Outcome logout = r.scrollToKey(Keys.LOGOUT_BUTTON, 8);
        if (logout == null) {
            throw new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT,
                    "设置页里找不到「退出登录」");
        }
        r.clickNode(Keys.LOGOUT_BUTTON, logout.node);

        StepRunner.Outcome confirm = r.findAny(Keys.LOGOUT_CONFIRM);
        if (confirm != null) {
            r.clickNode(Keys.LOGOUT_CONFIRM, confirm.node);
        }
    }

    /** 眼下这一页是不是「还没登录」的样子（登录入口／账号密码表单／一键登录页）。 */
    private static boolean onLoginPage(StepRunner r) throws StepRunner.StepFailure {
        return r.findAny(Keys.LOGIN_ENTRY, Keys.LOGIN_ACCOUNT_FIELD, Keys.LOGIN_ONE_TAP) != null;
    }

    /** 走到登录页：可能是账号密码表单，也可能是一键登录那一页。 */
    private static void openLoginPage(StepRunner r) throws StepRunner.StepFailure {
        if (r.findAny(Keys.LOGIN_ACCOUNT_FIELD, Keys.LOGIN_ONE_TAP) != null) return;
        StepRunner.Outcome entry = r.findAny(Keys.LOGIN_ENTRY);
        if (entry == null) {
            r.click(Keys.MINE_TAB, NAV_TIMEOUT);
            entry = r.findAny(Keys.LOGIN_ENTRY);
        }
        if (entry != null) {
            r.clickNode(Keys.LOGIN_ENTRY, entry.node);
        }
        r.waitForAny(NAV_TIMEOUT, Keys.LOGIN_ACCOUNT_FIELD, Keys.LOGIN_ONE_TAP);
    }
}
