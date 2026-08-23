package com.example.blb.auto;

/** selectors.json 里用到的 key，集中一处避免拼错。 */
public final class Keys {

    public static final String HOME_READY = "home_ready";
    public static final String SHELF_TAB = "shelf_tab";
    public static final String LIBRARY_TAB = "library_tab";
    public static final String MINE_TAB = "mine_tab";
    public static final String NICKNAME = "nickname";
    public static final String COUPONS_VALUE = "coupons_value";

    public static final String CHECKIN_ENTRY = "checkin_entry";
    public static final String CHECKIN_BUTTON = "checkin_button";
    public static final String CHECKIN_DONE = "checkin_done";
    /**
     * 签到面板本身（连签天数那一块／整页的「每日签到」标题）。
     *
     * <p>菠萝包签完之后不留任何「已签到」字样 —— 今天那一格直接变回星期名，所以
     * 「面板在、但『点击签到』不在」是判断「今天已经签过」的唯一可靠判据。
     */
    public static final String CHECKIN_DIALOG = "checkin_dialog";
    /** 签到成功页上的「+8代券」，只用来把奖励写进日志。 */
    public static final String CHECKIN_REWARD = "checkin_reward";
    /** 「看小视频领代券」入口。辅助点击模式下由脚本替你按下，视频照常完整播放。 */
    public static final String AD_REWARD = "ad_reward";
    /** 「今日还剩 N 次」这类计数。既用来告诉你还差几个，也是「奖励到没到账」的唯一硬证据。 */
    public static final String AD_REMAINING = "ad_remaining";
    /** 播放页上的「奖励将于 N 秒后发放」。它在＝视频页已经顶上来了，而且奖励还没到手。 */
    public static final String AD_PENDING = "ad_pending";
    /**
     * 播放页上的「恭喜获得奖励」。它在＝这一个已经算看完了，可以放心离开播放页。
     *
     * <p>选择器刻意不认「奖励已发放」——签到成功页上那句是「奖励已发放到账号」，说的是签到的
     * 奖励；认了它，广告还没播就会被当成已经领到。
     */
    public static final String AD_EARNED = "ad_earned";
    /**
     * 把视频停住、只留「点进落地页浏览 N 秒」这一条领奖路径的那张卡。
     *
     * <p>实测两家 SDK 都有：优量汇写「10秒更快拿奖」，穿山甲写「去浏览 15秒 免看此广告 /
     * 我要加速 / 请勿中断浏览以免任务失败」。共同点是卡上没有「关掉接着看」的键，右上角关闭键
     * 只弹二次确认，关掉确认后卡还在、画面还停着 —— 挂 6 分钟顶栏文案一个字都不变，等不出奖励。
     * 所以撞上它时只有两条路：你开了跳转开关就替你按那颗跳转键，没开就放弃这一个广告 ——
     * 不装作看完，也不把你留在那儿干等。
     */
    public static final String AD_PROMO = "ad_promo";
    /** 上面那张卡里写着的「要浏览几秒」，用来决定在落地页停多久。停不满等于白跳一趟。 */
    public static final String AD_DWELL_HINT = "ad_dwell_hint";
    /**
     * 播放页右上角那颗「跳过」。
     *
     * <p><b>只用来放弃、绝不用来领奖。</b>唯一的按它的场合是：我们已经决定不看这一支广告
     * （撞上 {@link #AD_PROMO} 那张卡而你没开跳转开关），而这个播放页又把返回键吃掉了、
     * 退不出来。那时候奖励本来就拿不到，按「跳过」只是把它了结掉。正常播放中一律不碰它 ——
     * 按它领不到奖励，还等于让广告主白付一次曝光。
     */
    public static final String AD_SKIP = "ad_skip";
    /** 奖励没到手时按关闭/返回弹出的「确认要离开吗」。 */
    public static final String AD_LEAVE_CONFIRM = "ad_leave_confirm";
    /** 上面那个确认框里的「放弃奖励离开」。只在我们自己决定放弃这个广告时才按。 */
    public static final String AD_ABANDON = "ad_abandon";
    /** 视频播完后那颗「领取奖励」（实测菠萝包这边是「开心收下」）。 */
    public static final String AD_CLAIM = "ad_claim";
    /**
     * 广告中途暂停时那颗让视频接着播的键（实测优量汇二次确认里写的是「抓住奖励机会」）。
     *
     * <p>这一组只认「让视频接着播」的键。刻意不含「跳转／下载／打开／去看看」那类会跳到别的
     * App 的按钮 —— 那一下是广告主按点击和安装付费的动作，替你按就变成骗点击了；而且跳出去
     * 之后本 App 看不见那个界面，手按不动的人会被卡在那儿。遇到那种弹窗走全局返回退回来。
     */
    public static final String AD_RESUME = "ad_resume";
    /**
     * 广告里那颗会跳到别的 App／落地页的按钮（下载、打开、去看看、立即体验……）。
     *
     * <p>默认<b>不</b>按（{@link com.example.blb.util.Prefs#isAdJump}，默认关）。按它是一次
     * 广告主要另外付费的点击，所以只在你明确开了这个开关、并且你人就在屏幕前看着的时候才按；
     * 定时任务里永远不按。就算开着，也只在 {@link #AD_PROMO} 那张卡把视频停住、不点就拿不到
     * 奖励的时候才按 —— 正常播着的广告底部常驻的「立即打开」一律不按。按下去之后会在落地页
     * 停留一会儿让你看清，再用全局返回把你带回广告页接着播 —— 手按不动的人最怕的是被留在
     * 别的 App 里出不来。
     */
    public static final String AD_JUMP = "ad_jump";
    /**
     * 播完之后的关闭键。只在播满最短时长之后才允许点，且不匹配「跳过」。
     *
     * <p>实测优量汇播放页右上角那颗既没有 id 也没有文字，选择器认不出来，所以那一家靠全局返回
     * 离开播放页；这一组留给认得出关闭键的其它 SDK（穿山甲这类）。
     */
    public static final String AD_CLOSE = "ad_close";

    public static final String CAPTCHA_HINT = "captcha_hint";

    public static final String SETTINGS_ENTRY = "settings_entry";
    public static final String LOGOUT_BUTTON = "logout_button";
    public static final String LOGOUT_CONFIRM = "logout_confirm";
    public static final String LOGIN_ENTRY = "login_entry";
    public static final String LOGIN_SWITCH_TO_PASSWORD = "login_switch_to_password";
    public static final String LOGIN_AGREE_CHECKBOX = "login_agree_checkbox";
    public static final String LOGIN_ACCOUNT_FIELD = "login_account_field";
    public static final String LOGIN_PASSWORD_FIELD = "login_password_field";
    public static final String LOGIN_SUBMIT = "login_submit";
    public static final String LOGIN_ERROR = "login_error";
    /** 登录页的「本机号码一键登录」按钮。 */
    public static final String LOGIN_ONE_TAP = "login_one_tap";
    /** 「切换手机号或邮箱登录」——从一键登录页进到账号密码表单的入口。 */
    public static final String LOGIN_SWITCH_PHONE = "login_switch_phone";
    /** 三方登录图标。授权页在别的 App 里，这三个只用来点开、之后交给你。 */
    public static final String LOGIN_WECHAT = "login_wechat";
    public static final String LOGIN_QQ = "login_qq";
    public static final String LOGIN_WEIBO = "login_weibo";

    public static final String SEARCH_ENTRY = "search_entry";
    public static final String SEARCH_FIELD = "search_field";
    public static final String SEARCH_SUBMIT = "search_submit";
    public static final String CATALOG_ENTRY = "catalog_entry";
    /** 目录页的「下载」——批量购买页的入口，也是唯一能读到价格的购买界面。 */
    public static final String DOWNLOAD_ENTRY = "download_entry";
    /** 章节行上的锁标记。有锁＝这个号还没有这一章，是判断「买没买到」的唯一硬证据。 */
    public static final String CHAPTER_LOCKED = "chapter_locked";
    /** 章节行上的「已下载」标记，买到之后会出现，作为锁消失之外的正面佐证。 */
    public static final String CHAPTER_OWNED = "chapter_owned";
    /** 「已选 N 章」，用来确认点选真的生效了。 */
    public static final String SELECTED_COUNT = "selected_count";
    /** 「需 20 火券」，选中之后才出现。 */
    public static final String PRICE_HINT = "price_hint";
    public static final String SUBSCRIBE_BUTTON = "subscribe_button";
    public static final String SUBSCRIBE_CONFIRM = "subscribe_confirm";
    public static final String SUBSCRIBE_DONE = "subscribe_done";
    public static final String INSUFFICIENT_COUPONS = "insufficient_coupons";

    /** 签到流程最少需要这些 key 可用，设置页自检就查这批。 */
    public static final String[] REQUIRED_FOR_CHECKIN = {
            MINE_TAB, CHECKIN_ENTRY, CHECKIN_BUTTON, CHECKIN_DONE, CHECKIN_DIALOG, CAPTCHA_HINT};

    /** 切换账号还额外需要这些。 */
    public static final String[] REQUIRED_FOR_SWITCH = {
            NICKNAME, SETTINGS_ENTRY, LOGOUT_BUTTON, LOGIN_ACCOUNT_FIELD,
            LOGIN_PASSWORD_FIELD, LOGIN_SUBMIT};

    /**
     * 自动订阅还额外需要这些。搜书是进目录的唯一入口，所以搜索框也算必需；
     * 章节锁与「已选 N 章」是护栏本身（判断买没买到、点选有没有生效），少一个就不许跑。
     */
    public static final String[] REQUIRED_FOR_SUBSCRIBE = {
            SEARCH_ENTRY, SEARCH_FIELD, CATALOG_ENTRY, DOWNLOAD_ENTRY,
            CHAPTER_LOCKED, SELECTED_COUNT, SUBSCRIBE_BUTTON, INSUFFICIENT_COUPONS};

    private Keys() {
    }
}
