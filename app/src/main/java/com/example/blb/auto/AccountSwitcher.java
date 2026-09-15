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
 * <p>还没记昵称时，当前登录身份未知，必须走一次这个账号指定的登录方式，再记录登录后的昵称。
 * 本地只启用一个账号不代表菠萝包当前登录的就是它，不能据此把别人的昵称和购买记录归给它。
 *
 * <p>还有一处刻意的顺序：昵称对不上时也不去猜，退登＋重登是<b>唯一</b>纠正手段，
 * 而它的前提是这个号真的登得回来（见下面那条硬规矩）。
 *
 * <p><b>五种登录方式全部不用人插手</b>（2026-08-23 逐页实测校准）。这条链路是给手指动不了的人
 * 用的，所以整个类里<b>没有一处「停下来等你点」</b> —— 唯一会挂起等人的是验证码
 * （{@link StepRunner#guardCaptcha()}），那一处不能自动，也不该自动。
 *
 * <p>各方式实测走法：
 * <ul>
 *   <li><b>账号密码</b>：登录页先按「切换手机号或邮箱登录」才有输入框；按过之后用户协议
 *       已经自动勾上（实测 {@code check_confirm_protocol} 的 checked 就是 true）。</li>
 *   <li><b>本机号码一键登录</b>：勾同意 → 按中间那颗大按钮。号码由 SIM 卡决定，
 *       只能登成本机那一个号。</li>
 *   <li><b>微信 / 微博</b>：勾同意 → 点底部那个图标，点完就直接登回菠萝包了，没有中间页。</li>
 *   <li><b>QQ</b>：同上，但会跳进 com.tencent.mobileqq 要按一颗「同意」。那一颗由
 *       {@link StepRunner#confirmThirdPartyAuth} 隔着界外硬闸去按 —— 只在等这颗键的那几秒里
 *       允许读 QQ 的树，只匹配 {@link Keys#LOGIN_AUTH_CONFIRM}，别的节点一个都不读。</li>
 * </ul>
 *
 * <p><b>顺序上的一条硬规矩</b>：任何「这个号根本登不回来」的判断都必须在退登<b>之前</b>做完
 * （目前是账号密码方式没存密码这一种）。先退登再发现登不回去，等于把人锁在门外。
 */
public final class AccountSwitcher {

    private static final long NAV_TIMEOUT = 10_000;
    private static final long LOGIN_TIMEOUT = 25_000;
    /** 等三方 App 的授权页出来并按下「同意」的窗口。QQ 冷启动可能要好几秒。 */
    private static final long AUTH_TIMEOUT = 20_000;
    /** 退登后「我的」页停在底部，往回滚这么多次找「立即登录」。 */
    private static final int SCROLL_TO_TOP_TIMES = 6;

    private AccountSwitcher() {
    }

    /** 兼容原调用方；启用数量不再用于推断当前登录身份。 */
    public static void ensureLoggedIn(StepRunner r, Account account, AccountDao dao,
                                      boolean soleEnabled) throws StepRunner.StepFailure {
        ensureLoggedIn(r, account, dao);
    }

    public static void ensureLoggedIn(StepRunner r, Account account, AccountDao dao)
            throws StepRunner.StepFailure {
        // 返回键额度按「订阅流程留下的最深处」给：上一个号买完章之后我们站在
        // 选择章节页→目录→详情→搜索 这四层里，退到首页至少要 4 下。以前给 3 下，
        // 2026-08-24 15:26 那趟 8 个号里有 3 个刚好卡在这个临界点上 ——
        // 只留下一句「等 mine_tab 超时」，连登录都没开始。多按几下返回本身没有代价：
        // 一看到首页就立刻停，避免重复返回把已经找到的导航退掉。
        r.ensureHome(6);
        r.click(Keys.MINE_TAB, NAV_TIMEOUT);

        String current = r.readText(Keys.NICKNAME, 6_000);
        boolean known = !Texts.isBlank(account.nickname);
        if (known && !Texts.isBlank(current) && account.nickname.trim().equals(current.trim())) {
            return;
        }
        // 昵称未知与不匹配都需要验证登录；login 会在退登前先确认密码可用。
        login(r, account, dao);
    }

    private static void login(StepRunner r, Account account, AccountDao dao)
            throws StepRunner.StepFailure {
        String kind = account.loginKind == null ? Account.KIND_PASSWORD : account.loginKind;
        String password = null;
        if (Account.KIND_PASSWORD.equals(kind)) {
            // 退登之前就把密码取出来：取不到就根本别退，不然人会被锁在门外。
            password = readPassword(account);
        }

        r.log("开始切到「" + account.displayName() + "」（" + account.loginKindLabel() + "）");
        logoutIfNeeded(r);
        openLoginPage(r);

        switch (kind) {
            case Account.KIND_PASSWORD:
                submitPassword(r, account, password);
                break;
            case Account.KIND_PHONE_ONE_TAP:
                oneTap(r);
                break;
            default:
                thirdParty(r, account, kind);
                break;
        }
        finishLogin(r, account, dao);
    }

    // ---------- 各种登录方式 ----------

    /** 取明文密码。只在内存里传递，绝不写日志。取不出来就当场失败（此时还没退登）。 */
    private static String readPassword(Account account) throws StepRunner.StepFailure {
        if (!account.hasPassword()) {
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "账号「" + account.displayName() + "」没有保存密码，无法自动切换"
                            + "（还没退登，当前账号没动）");
        }
        try {
            return KeyStoreBox.open(account.encPassword, account.encIv);
        } catch (KeyStoreBox.CryptoException e) {
            throw new StepRunner.StepFailure(StepRunner.Kind.LOGIN_FAILED,
                    "取不出「" + account.displayName() + "」的密码：" + e.getMessage()
                            + "（还没退登，当前账号没动）");
        }
    }

    /**
     * 账号密码登录。
     *
     * <p>实测入口是一键登录页上那颗小字「切换手机号或邮箱登录」；按过之后表单里的用户协议
     * 已经自动勾上（{@code check_confirm_protocol} checked=true），所以 {@link #agree} 在这条
     * 路上正常是个空动作 —— 留着它是为了万一哪天默认变了。
     */
    private static void submitPassword(StepRunner r, Account account, String password)
            throws StepRunner.StepFailure {
        if (r.findAny(Keys.LOGIN_ACCOUNT_FIELD) == null) {
            r.click(Keys.LOGIN_SWITCH_PHONE, NAV_TIMEOUT);
        }
        // 有些版本切过去还是验证码登录，要再点一下「密码登录」；没有这颗就说明已经是表单。
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

    /** 本机号码一键登录：勾同意，按中间那颗大按钮。 */
    private static void oneTap(StepRunner r) throws StepRunner.StepFailure {
        agree(r);
        r.click(Keys.LOGIN_ONE_TAP, NAV_TIMEOUT);
        r.guardCaptcha();
    }

    /**
     * 微信／QQ／微博：勾同意，点底部那个图标，需要的话再隔着硬闸按那颗「同意」。
     *
     * <p>三个图标实测都没有 text 也没有 desc，只能按 id 认（{@code wxBtnLayout} /
     * {@code qqBtnLayout} / {@code sinaBtnLayout}）。微信、微博点完就回菠萝包了，
     * {@link StepRunner#confirmThirdPartyAuth} 那一趟会立刻发现前台已经是 com.sfacg 并返回 false，
     * 一个节点都不会读。
     */
    private static void thirdParty(StepRunner r, Account account, String kind)
            throws StepRunner.StepFailure {
        String key = keyFor(kind);
        if (key == null) {
            throw new StepRunner.StepFailure(StepRunner.Kind.CONFIG,
                    "不认识的登录方式：" + kind);
        }
        String name = account.loginKindLabel();
        agree(r);
        r.click(key, NAV_TIMEOUT);

        boolean pressed = r.confirmThirdPartyAuth(name, AUTH_TIMEOUT);
        if (!r.isTargetForeground()) {
            r.log(pressed
                    ? "按完「同意」还没回到菠萝包，主动把它拉回前台"
                    : "还停在 " + r.activePackage() + "，没找到要按的授权键，先把菠萝包拉回前台");
            r.launchTarget(LOGIN_TIMEOUT);
        }
        r.guardCaptcha();
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

    /**
     * 勾「本人已阅读并同意…」。<b>已经勾了就绝不能再点</b> —— 再点一下是取消。
     *
     * <p>一键登录页那个 {@code authsdk_checkbox_view} 是外层 FrameLayout，它自己的 checked
     * 恒为 false；真正读得出状态的是里面那颗 CheckBox。所以 selectors.json 里候选顺序刻意让
     * 能读出状态的节点排在前面（见 {@code _note_login_agree}），否则外层先命中就会把已经勾上的
     * 协议取消掉，而这种错误在日志里看不出来 —— 只表现成「按了登录没反应」。
     */
    private static void agree(StepRunner r) throws StepRunner.StepFailure {
        StepRunner.Outcome agree = r.findAny(Keys.LOGIN_AGREE_CHECKBOX);
        if (agree == null) {
            r.log("这一页没有找到用户协议的勾选框（可能本来就不需要勾）");
            return;
        }
        if (agree.node.checked()) return;
        r.clickNode(Keys.LOGIN_AGREE_CHECKBOX, agree.node);
        // 勾没勾上直接决定后面那颗登录键有没有用，所以复核一次并如实记下来。
        StepRunner.Outcome after = r.findAny(Keys.LOGIN_AGREE_CHECKBOX);
        if (after != null && !after.node.checked()) {
            r.log("用户协议好像没勾上（点过一次了），登录键可能会没反应");
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
        if (!trimmed.equals(account.nickname)) {
            account.nickname = trimmed;
            if (dao != null) dao.setNickname(account.id, trimmed);
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

    /**
     * 当前有人登录才需要退出；已经在未登录态直接进登录表单。
     *
     * <p>实测路径（2026-08-23，逐页 dump 校准）：「我的」页往下翻 4 次才看得见「设置」→
     * 设置页往下翻 4 次才看得见「退出登录」→ <b>按下去没有二次确认框</b>，直接就退了。
     * 所以 {@code logout_confirm} 是「有就点、没有也正常」，不能等它。
     */
    private static void logoutIfNeeded(StepRunner r) throws StepRunner.StepFailure {
        if (onLoginPage(r)) return;
        // 上一趟可能把「我的」页留在了底部，「立即登录」不在可见树里 —— 那样会被误判成
        // 「还登着人」，白跑一趟退登。先滚回顶部再判一次。
        r.scrollToTop(SCROLL_TO_TOP_TIMES);
        if (onLoginPage(r)) return;

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

        // 实测没有确认框。有就点，没有也不算异常，所以只看当前屏、不等。
        StepRunner.Outcome confirm = r.findAny(Keys.LOGOUT_CONFIRM);
        if (confirm != null) {
            r.clickNode(Keys.LOGOUT_CONFIRM, confirm.node);
        }
        r.log("已退出登录");
    }

    /** 眼下这一页是不是「还没登录」的样子（登录入口／账号密码表单／一键登录页）。 */
    private static boolean onLoginPage(StepRunner r) throws StepRunner.StepFailure {
        return r.findAny(Keys.LOGIN_ENTRY, Keys.LOGIN_ACCOUNT_FIELD, Keys.LOGIN_ONE_TAP) != null;
    }

    /**
     * 走到登录页：正常是那一页一键登录页（阿里云号码认证，属于 com.sfacg 进程）。
     *
     * <p>关键的一步是<b>滚回顶部</b>：退登之后直接回到「我的」页，而那个 ScrollView 还停在底部
     * （{@code top_layout} 高度塌成 2px），「立即登录」根本不在可见树里 —— 不滚回去就永远点不到，
     * 整条切号链路断在这儿。这是实测踩到的，不是保险。
     */
    private static void openLoginPage(StepRunner r) throws StepRunner.StepFailure {
        if (r.findAny(Keys.LOGIN_ACCOUNT_FIELD, Keys.LOGIN_ONE_TAP) != null) return;

        StepRunner.Outcome entry = r.findAny(Keys.LOGIN_ENTRY);
        if (entry == null) {
            r.scrollToTop(SCROLL_TO_TOP_TIMES);
            entry = r.findAny(Keys.LOGIN_ENTRY);
        }
        if (entry == null) {
            r.click(Keys.MINE_TAB, NAV_TIMEOUT);
            r.scrollToTop(SCROLL_TO_TOP_TIMES);
            entry = r.findAny(Keys.LOGIN_ENTRY);
        }
        if (entry == null) {
            throw new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT,
                    "「我的」页上找不到「立即登录」，进不了登录页");
        }
        r.clickNode(Keys.LOGIN_ENTRY, entry.node);
        r.waitForAny(NAV_TIMEOUT, Keys.LOGIN_ACCOUNT_FIELD, Keys.LOGIN_ONE_TAP);
    }
}
