package com.example.blb.auto;

/** selectors.json 里用到的 key，集中一处避免拼错。 */
public final class Keys {

    public static final String HOME_READY = "home_ready";
    public static final String SHELF_TAB = "shelf_tab";
    public static final String LIBRARY_TAB = "library_tab";
    public static final String MINE_TAB = "mine_tab";
    public static final String NICKNAME = "nickname";
    public static final String COUPONS_VALUE = "coupons_value";
    /** 我的钱包页上单独那颗代券数字（{@code tv_voucher}）。 */
    public static final String VOUCHERS_VALUE = "vouchers_value";
    /**
     * 「我的」页那一行余额的两个<b>标签</b>（火券／代券）。
     *
     * <p>数字本身没有 resource-id、也没有任何可匹配的文字，只能靠标签定位、再取它正上方
     * 那个数字，见 {@link NodeMatcher#countAbove}。所以这两个 key 配的是标签，不是数字。
     */
    public static final String BALANCE_FIRE_LABEL = "balance_fire_label";
    public static final String BALANCE_VOUCHER_LABEL = "balance_voucher_label";

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
    /**
     * 「已领完」——今天的广告次数用光了，这是最硬的那条判据。
     *
     * <p>2026-08-23 19:19 实测（用户截图）：领完之后签到面板上那颗入口（{@code sign_in_ad_goto}）
     * <b>整个从树里消失</b>，原地换成一颗灰的 {@code sign_in_ad_finished}=「已领完」，而计数那格
     * （{@code sign_in_ad_count}）的文案从「今日还剩 N 次」变成了「明日更新次数」—— 一个数字都
     * 没有。旧写法里 {@link #AD_REMAINING} 第一条候选是纯 id、不看文字，抢先命中「明日更新次数」
     * 就读出 -1，于是脚本按配置的每日个数去点那个已经没有了的入口，每个号白等 20 秒。
     *
     * <p>还有一件必须知道的事（用户 2026-08-23 说明）：那 5 次是<b>所有账号共用</b>的一天配额，
     * 不是每号 5 次。第一个号看完 5 支，后面 7 个号打开面板就是这张「已领完」—— 所以这一组
     * 认得准，等于后面每个号都省下一次白等。
     */
    public static final String AD_EXHAUSTED = "ad_exhausted";
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
     * 菠萝包自己弹的那张代券发放卡：「+3 ／ 开心收下 ／ 已发放到"我的-我的钱包"中」。
     *
     * <p>它和 {@link #AD_CLAIM} 的区别是「认得准」：这一组只认这张卡自己的节点
     * （{@code welfare_container} 里那颗 {@code tvConfirm}＝「开心收下」），所以可以在
     * <b>任何地方</b>放心地按下去 —— 包括 {@link StepRunner#ensureHome} 的清障环节。
     *
     * <p>2026-08-24 09:28 实测的那次事故就靠它兜住：第 3 支广告领完之后菠萝包过几秒才弹这张卡，
     * 脚本查得太早（查了一次没有就走了），于是这张<b>模态</b>卡一直盖在首页上 ——
     * 后面 7 个号的 {@code ensureHome} 全部只会按返回（关不掉它），每个号都在「等 mine_tab」
     * 超时，整趟只有第 1 个号签到成功。按下「开心收下」既是把代券真正收进账号
     * （不按就不算收完，见 memory 里的 blb-ad-reward-grant-dialog），也是唯一能清掉它的动作。
     */
    public static final String REWARD_GRANT = "reward_grant";
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
    /** 三方登录图标。实测微信和微博点一下就直接登回来了，只有 QQ 还要在它自己的 App 里确认一次。 */
    public static final String LOGIN_WECHAT = "login_wechat";
    public static final String LOGIN_QQ = "login_qq";
    public static final String LOGIN_WEIBO = "login_weibo";
    /**
     * 三方 App 里那颗授权确认键（实测 QQ 上是 Button text=同意）。
     *
     * <p><b>这是唯一允许在 com.sfacg 之外匹配的一组选择器。</b>界外硬闸
     * （{@link BlbAccessibilityService#authRoot()}）只在等这一颗键的时候开，而且只用这一组去找；
     * 那三个 App 里其它任何节点都不读、不点、不写日志、不导出。所以这一组的正则必须锚得很死，
     * 只认「同意／允许／确认登录」这类明确的授权键。
     */
    public static final String LOGIN_AUTH_CONFIRM = "login_auth_confirm";

    public static final String SEARCH_ENTRY = "search_entry";
    public static final String SEARCH_FIELD = "search_field";
    public static final String SEARCH_SUBMIT = "search_submit";
    /**
     * 「书名画在哪个 id 上」的候选清单：搜索建议行（{@code tv_think_text}）、结果页书名
     * （{@code tvbBookTitle}）、卡片式书名（{@code novel_name}／{@code hot_novel_name}）。
     *
     * <p>只写 id、<b>不</b>写文本：书名跑起来才知道，由
     * {@link StepRunner#findRowWithText} 现场拼上去。
     *
     * <p>真机 2026-08-24 校准出来的教训：搜索输入框 {@code inputSearch} 的文本也正好等于刚
     * 输进去的书名，纯按文本找会先命中输入框、点它什么都不会发生 —— 这就是「点了书名但没进
     * 详情页」的全部原因。限定 id 同时把输入框和「以“…”为关键字进行搜索」那条排掉。
     */
    public static final String NOVEL_TITLE_ROW = "novel_title_row";
    /** 搜索建议里的「以“…”为关键字进行搜索」那一条 —— 这个 App 没有可见的「搜索」键，点它才提交。 */
    public static final String SEARCH_KEYWORD_ROW = "search_keyword_row";
    public static final String CATALOG_ENTRY = "catalog_entry";
    /** 目录页的「下载」——批量购买页的入口，也是唯一能读到价格的购买界面。 */
    public static final String DOWNLOAD_ENTRY = "download_entry";
    /**
     * 选择章节页每一行的标题节点（{@code title}）。
     *
     * <p>{@link CatalogScanner} 靠它把整本书的章节顺序读出来。行文本是「67   周日工作」这种
     * 「标号 + 标题」合在一个节点里的写法，标号是阿拉伯数字、空格数不固定。
     * 卷标题行用的也是这个 id，靠「行首没有标号」区分（见 {@link com.example.blb.util.Texts#rowChapterNo}）。
     */
    public static final String CHAPTER_ROW_TITLE = "chapter_row_title";
    /** 选择章节页右下角那颗「回到顶部」（{@code goto_top}），用来在扫完之后一步回到第一章。 */
    public static final String CHAPTER_LIST_TOP = "chapter_list_top";
    /**
     * 章节行上的锁标记。它只说明「这是付费章」，<b>不说明买没买</b>。
     *
     * <p>2026-08-24 21:21 第一次真买踩的就是这个坑：券确实扣了（53→33、23→3），可这两行的
     * {@code title_lock} 一直在，于是判成失败、账本一条没记。用户 21:57 的截图解释了原因 ——
     * 买到的行锁变成<b>打开的锁</b>（红边白底），没买的行是<b>锁上的锁</b>（红色实心），
     * 而这两种锁在无障碍树里是同一个空文本节点，分不出来。
     *
     * <p>所以判据换成 {@link #CHAPTER_OWNED} ＋ {@link #CHAPTER_SELECTABLE}；这一组只用来
     * 认「这是付费章」。
     */
    public static final String CHAPTER_LOCKED = "chapter_locked";
    /**
     * 章节行上的「已下载」（{@code title_check}）＝<b>这个号已经能看这一章</b>。
     *
     * <p>免费章下载完是它，付费章买完也是它（「立即下载」是买＋下载一起做的），所以它既是
     * 「本来就有」的判据，也是「刚刚买成了」唯一的正面硬证据。
     */
    public static final String CHAPTER_OWNED = "chapter_owned";
    /**
     * 行右边那个可勾选的圆圈（{@code item_cb}）＝这一行还能被勾上，也就是这个号还没有它。
     *
     * <p>已下载的行右边写的是「已下载」三个字、没有圆圈 —— 这解释了为什么买过的行点下去
     * 「已选」是 0 章。它是 {@link #CHAPTER_OWNED} 的反面佐证。
     */
    public static final String CHAPTER_SELECTABLE = "chapter_selectable";
    /** 「已选 N 章」，用来确认点选真的生效了。 */
    public static final String SELECTED_COUNT = "selected_count";
    /** 「需 20 火券」，选中之后才出现。这是<b>标价</b>，不是实付，判断花不花火券要看 {@link #PAY_DETAIL}。 */
    public static final String PRICE_HINT = "price_hint";
    /**
     * 「实付0火券+12代券」（{@code tvTips}）—— 「只花代券」这条硬约束唯一的判据。
     *
     * <p>菠萝包先拿代券抵扣，抵不完的差额才动火券，而这句话把结果直接写了出来。
     * 用户不充值火券，所以只有实付火券 == 0 才允许按下「立即下载」；读不出来一律当成不够。
     */
    public static final String PAY_DETAIL = "pay_detail";
    public static final String SUBSCRIBE_BUTTON = "subscribe_button";
    public static final String SUBSCRIBE_CONFIRM = "subscribe_confirm";
    public static final String SUBSCRIBE_DONE = "subscribe_done";
    public static final String INSUFFICIENT_COUPONS = "insufficient_coupons";

    /**
     * 「我的」页上「代券」那一格（点进去是钱包页）。
     *
     * <p>它和 {@link #BALANCE_VOUCHER_LABEL} 指的是同一个标签节点，但用途相反：那一条是
     * 「读它正上方的数字」，这一条是「点它的可点祖先」。数字和标签都没有 resource-id，
     * 所以两条都只能按文本认（2026-08-25 实测那一列是 {@code LinearLayout [552,687][797,798]}）。
     */
    public static final String VOUCHER_ENTRY = "voucher_entry";
    /** 钱包页里的「订阅清单」（{@code tv_payed}，可点祖先 {@code llt_payed_list}）。 */
    public static final String SUBSCRIBED_LIST_ENTRY = "subscribed_list_entry";
    /** 订阅清单里一行的书名（{@code tvbBookTitle}）—— 从它往上找到整行，再在行内读别的字段。 */
    public static final String SUBSCRIBED_BOOK_TITLE = "subscribed_book_title";
    /**
     * 订阅清单里一行的摘要（{@code tvbBookAutor}），实测文案是「2章节 - 0火券」。
     *
     * <p>这是「服务器说这个号在这本书上订了几章、花了多少火券」<b>唯一</b>的来源，
     * 也是对账的判据：章数要和账本里的付费记录条数一致，火券必须是 0
     * （用户不充值火券）。见 {@link VoucherLedger}。
     */
    public static final String SUBSCRIBED_BOOK_SUMMARY = "subscribed_book_summary";
    /** 订阅清单里一行的日期（{@code tvbBookDesc}），只写进日志。 */
    public static final String SUBSCRIBED_BOOK_DATE = "subscribed_book_date";

    /**
     * 「订阅明细」页到了没有（页标题 {@code title_tv}＝「订阅明细」，列表 {@code baseListView}）。
     *
     * <p>2026-08-25 用户指出：清单那一行要点的是<b>整行</b>（行右上角那个「&gt;」只是装饰，
     * 空文本、没有 id、不可点），不是「查看目录」——「查看目录」进去是整本书的目录列表。
     * 点整行进去这一页才是<b>逐章明细</b>：这个号在这本书上买过的每一章各一条。
     */
    public static final String SUBSCRIBED_DETAIL_READY = "subscribed_detail_ready";
    /**
     * 明细里一条的正文（{@code tvDesc}），实测原文「世界线的变动，学生会长的恋爱 50 订婚事宜，梦玲失踪」
     * ＝ 卷名 + 空格 + <b>章号</b> + 空格 + 章标题。逐章对账的章号就是从这里来的。
     */
    public static final String SUBSCRIBED_DETAIL_DESC = "subscribed_detail_desc";
    /** 明细里一条的日期（{@code tvTime}）—— 「刚买完清单还没同步」要靠它区分。 */
    public static final String SUBSCRIBED_DETAIL_TIME = "subscribed_detail_time";
    /** 明细里一条右侧那个数字（<b>没有 id</b>，只能按「整段都是数字」认，而且必须在行内查）。 */
    public static final String SUBSCRIBED_DETAIL_AMOUNT = "subscribed_detail_amount";
    /** 明细里一条右侧的币种（同样没有 id）：正常永远是「代券」，出现「火券」就是硬约束被破坏。 */
    public static final String SUBSCRIBED_DETAIL_CURRENCY = "subscribed_detail_currency";

    /** 签到流程最少需要这些 key 可用，设置页自检就查这批。 */
    public static final String[] REQUIRED_FOR_CHECKIN = {
            MINE_TAB, CHECKIN_ENTRY, CHECKIN_BUTTON, CHECKIN_DONE, CHECKIN_DIALOG, CAPTCHA_HINT};

    /** 切换账号还额外需要这些。 */
    public static final String[] REQUIRED_FOR_SWITCH = {
            NICKNAME, SETTINGS_ENTRY, LOGOUT_BUTTON, LOGIN_ENTRY, LOGIN_ACCOUNT_FIELD,
            LOGIN_PASSWORD_FIELD, LOGIN_SUBMIT, LOGIN_AGREE_CHECKBOX, LOGIN_ONE_TAP,
            LOGIN_SWITCH_PHONE, LOGIN_WECHAT, LOGIN_QQ, LOGIN_WEIBO, LOGIN_AUTH_CONFIRM};

    /**
     * 自动订阅还额外需要这些。搜书是进目录的唯一入口，所以搜索框也算必需；
     * 「已下载」、勾选圈、「已选 N 章」、行标题、「实付」是四道护栏本身（买没买到、点选有没有
     * 生效、勾中的是不是单独一章、这一章会不会动到火券），少一个就不许跑。
     */
    public static final String[] REQUIRED_FOR_SUBSCRIBE = {
            SEARCH_ENTRY, SEARCH_FIELD, NOVEL_TITLE_ROW, CATALOG_ENTRY, DOWNLOAD_ENTRY,
            CHAPTER_ROW_TITLE, CHAPTER_LOCKED, CHAPTER_OWNED, CHAPTER_SELECTABLE,
            SELECTED_COUNT, PAY_DETAIL, SUBSCRIBE_BUTTON, INSUFFICIENT_COUPONS};

    private Keys() {
    }
}
