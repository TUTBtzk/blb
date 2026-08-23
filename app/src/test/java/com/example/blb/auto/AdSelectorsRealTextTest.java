package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * 把真机上抓下来的广告文案钉在这儿。
 *
 * <p>广告这一组选择器只能靠文字命中（两家 SDK 的播放页都没有 resource-id、没有 clickable
 * 节点），文案又是各广告主自己写的，改一个字就可能漏判或误判。所以每次实测到新文案都补一条，
 * 保证以后调正则不会把已经验证过的情形弄坏 —— 尤其是那两条「不该命中」的。
 */
public class AdSelectorsRealTextTest {

    private static Map<String, List<Selector>> bundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json", f.isFile());
        return SelectorSet.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    /** 整屏上任一段文字命中这个 key 就算命中——真机上文案是散在很多节点里的。 */
    private static NodeView hit(Map<String, List<Selector>> m, String key, String... screenTexts) {
        FakeNode root = FakeNode.node();
        for (String t : screenTexts) root.add(FakeNode.text(t));
        NodeMatcher.Hit h = NodeMatcher.find(root, m.get(key));
        return h == null ? null : h.node;
    }

    // 2026-08-23 实测，穿山甲激励视频里那张把画面定住的卡（文案是分成好几个节点的）。
    // 顺序照 p1.xml 的 DFS 原样排：被弹卡盖住的那张下载卡（「立即下载」，id=5db6c3，第 138 个
    // 节点）在前，弹卡自己那颗跳转键（「我要加速」，第 179 个节点）在后 —— ad_jump 的 topmost
    // 就是靠这个顺序挑到弹卡上那颗的，把顺序改了这条用例就不再验证真实情形了。
    private static final String[] PANGLE_PROMO = {
            "美团", "立即下载", "开发者：", "北京三快科技有限公司",
            "去浏览", "15秒", "免看此广告", "我要加速", "可提前13秒领奖",
            "请勿中断浏览以免任务失败", "去体验15秒可立即领奖", "跳过", "反馈", "广告"};

    // 2026-08-23 截图实测，穿山甲的第二种卡：「获得加速机会！」+「去看15秒可直接拿奖励」，
    // 卡上只有一颗键「我要直接拿奖励」，卡自己写明它是跳转（「上滑或点击跳转到详情页或第三方应用」）。
    private static final String[] PANGLE_PROMO_ACCELERATE = {
            "立即下载", "获得加速机会！", "去看15秒可直接拿奖励",
            "上滑或点击跳转到详情页或第三方应用", "我要直接拿奖励",
            "去体验15秒可立即领奖", "跳过", "反馈", "广告"};

    // 2026-08-23 截图实测，穿山甲的第三种卡：文案被 Lynx 拆成「去体验」「9秒」「可立即领奖」，
    // 键就叫「去体验」，右上角还有「6s后自动放弃」的倒计时 —— 所以 ad_promo 必须在轮询里
    // 排在 ad_resume／ad_claim 之前，晚一轮就错过窗口了。
    private static final String[] PANGLE_PROMO_EXPERIENCE = {
            "立即下载", "去体验", "9秒", "可立即领奖", "可提前19秒领奖", "6s后自动放弃",
            "跳过", "反馈", "广告"};

    // 优量汇那张卡（上一轮实测）。
    private static final String[] GDT_PROMO = {
            "10秒更快拿奖", "直接点击并浏览广告详情10秒即可获得奖励～", "我要更快拿奖"};

    // 2026-08-23 13:55 实测，同一张「获得加速机会」卡的另一种渲染：Lynx 把卡上的文案全渲成了
    // 占位键名，整张卡上唯一的中文只剩那颗按钮和顶栏那句。照 uiautomator dump 的原样抄下来
    // （115 个节点里所有带文字的），删掉任何一句都会重演「脚本以为在正常播放、干等 150 秒」。
    private static final String[] PANGLE_PROMO_PLACEHOLDER_KEYS = {
            "9fbc93749b50465991bc1a7e61e8d734~tplv-qgppglrh0x-noop", "京东",
            "李宁京东超级品牌日，京东独家首发", "部分内容由AI生成", "gift_box",
            "get_accelerate_chance", "title1", "second_underline", "15", "second_text", "title2",
            "reward_btn_shadow", "get_reward_btn_bg", "reward_btn_shine", "我要直接拿奖励",
            "2025723Group2777053140412", "反馈", "去体验15秒可立即领奖", "｜跳过", "广告"};

    // 2026-08-23 实测：按穿山甲顶栏那颗「跳过」之后弹出来的二次确认。它是独立窗口，
    // 所以屏幕上只有这几段字 —— 广告卡上的「免看此广告」这时候是看不见的。
    private static final String[] PANGLE_LEAVE_CONFIRM = {
            "温馨提示", "继续看", "28", "秒或下载安装", "即可领奖，确定要退出吗？",
            "去领取奖励", "坚持退出"};

    // 2026-08-23 14:14 实测，优量汇那支广告跳转 → 在闲鱼落地页停 14 秒 → 返回之后，
    // 播放页上弹出来的领奖弹窗（照 uiautomator dump 原样抄，代券这一支确实到账了：
    // 签到面板上「今日还剩」从 3 变成 2）。
    private static final String[] GDT_REWARD_DIALOG = {
            "10秒更快拿奖", "直接点击并浏览广告详情10秒即可获得奖励～",
            "已完成浏览10秒，提前获得奖励", "闲鱼", "快来！闲鱼好物品质佳，等你淘！",
            "继续了解详情", "了解更多",
            "应用名称：闲鱼 | 开发者：浙江阿里巴巴闲鱼网络科技有限公司", "恭喜获得奖励"};

    /**
     * 领奖弹窗必须先被认成「奖励到手」，而不是被当成还要点的促销卡。
     *
     * <p>这张弹窗上那几句促销文案（「10秒更快拿奖」「直接点击并浏览广告详情…」）领完奖之后
     * <b>还挂在屏幕上</b>，卡上那颗跳转键也还在（这次叫「继续了解详情」，在 ad_jump 里）。
     * 所以 {@code awaitPlayback} 里查 ad_earned 必须排在 ad_promo 之前 —— 顺序反了就会在
     * 奖励已经到手之后又按一次跳转键，那是替广告主白刷一次要付费的点击。
     */
    @Test
    public void gdtRewardDialogIsEarnedFirstEvenThoughThePromoTextIsStillThere()
            throws Exception {
        Map<String, List<Selector>> m = bundled();
        assertNotNull("「恭喜获得奖励／提前获得奖励」就是奖励到手",
                hit(m, Keys.AD_EARNED, GDT_REWARD_DIALOG));
        assertNotNull("促销文案领完奖之后还在，所以 ad_earned 必须先查",
                hit(m, Keys.AD_PROMO, GDT_REWARD_DIALOG));
        assertNotNull("卡上那颗跳转键也还在（这次叫「继续了解详情」）",
                hit(m, Keys.AD_JUMP, GDT_REWARD_DIALOG));
        for (String key : new String[]{Keys.AD_CLAIM, Keys.AD_RESUME, Keys.AD_SKIP}) {
            assertNull(key + " 不能在这张领奖弹窗上命中任何东西（出口只有右上角那颗 X）",
                    hit(m, key, GDT_REWARD_DIALOG));
        }
    }

    @Test
    public void pangleAndGdtPromoCardsAreBothRecognised() throws Exception {
        Map<String, List<Selector>> m = bundled();
        assertNotNull("穿山甲那张卡要认出来", hit(m, Keys.AD_PROMO, PANGLE_PROMO));
        assertNotNull("优量汇那张卡要认出来", hit(m, Keys.AD_PROMO, GDT_PROMO));
        assertNotNull("「获得加速机会」那张卡要认出来",
                hit(m, Keys.AD_PROMO, PANGLE_PROMO_ACCELERATE));
        assertNotNull("「去体验／可提前19秒领奖」那张卡要认出来",
                hit(m, Keys.AD_PROMO, PANGLE_PROMO_EXPERIENCE));
    }

    /**
     * 卡上文案全渲成占位键名的那一张也必须认出来。
     *
     * <p>2026-08-23 13:55 实测：这张卡就是这么丢掉一支广告的 —— 第一条候选（认卡上那几句中文）
     * 一句都没命中，{@code awaitPlayback} 于是把一张定住的卡当成「视频正在正常播放」，
     * 一直轮询到 150 秒上限。判据换成「卡上那颗跳转键在屏幕上」和「那几个占位键名在屏幕上」之后
     * 才认得出来。同时钉住：顶栏那句仍然能读出 15 秒，「｜跳过」这种带竖线的切分也要认成跳过键。
     */
    @Test
    public void promoCardIsRecognisedEvenWhenLynxRendersPlaceholderKeys() throws Exception {
        Map<String, List<Selector>> m = bundled();
        assertNotNull("这张卡必须认出来，认不出就是干等 150 秒白丢一支广告",
                hit(m, Keys.AD_PROMO, PANGLE_PROMO_PLACEHOLDER_KEYS));
        NodeView jump = hit(m, Keys.AD_JUMP, PANGLE_PROMO_PLACEHOLDER_KEYS);
        assertNotNull("跳转键就是那颗「我要直接拿奖励」", jump);
        assertTrue("实到的是：" + jump.text(), "我要直接拿奖励".equals(jump.text()));
        NodeView dwell = hit(m, Keys.AD_DWELL_HINT, PANGLE_PROMO_PLACEHOLDER_KEYS);
        assertNotNull("秒数从顶栏那句读", dwell);
        assertTrue(15 == com.example.blb.util.Texts.firstInt(dwell.text(), -1));
        NodeView skip = hit(m, Keys.AD_SKIP, PANGLE_PROMO_PLACEHOLDER_KEYS);
        assertNotNull("「｜跳过」也要认成跳过键（放弃这一支时才按）", skip);
        assertTrue("实到的是：" + skip.text(), "｜跳过".equals(skip.text()));
        for (String key : new String[]{Keys.AD_CLAIM, Keys.AD_RESUME, Keys.AD_EARNED}) {
            assertNull(key + " 不能在这张卡上命中任何东西",
                    hit(m, key, PANGLE_PROMO_PLACEHOLDER_KEYS));
        }
    }

    /**
     * 「跳过」弹出的二次确认要认得出来，而且只许按「坚持退出」。
     *
     * <p>「去领取奖励」听着像播完之后的领奖键，实际走的是这个框自己写的「下载安装」那条路 ——
     * 按下去就是替广告主制造一次要付费的下载点击。所以 ad_claim / ad_resume / ad_jump 一个都
     * 不许命中它：前两组是脚本默认就会替你按的键，第三组虽然要你单独开开关，但开着的时候
     * 也只该按卡上那颗明写着跳转的键，不该按这个把下载包装成领奖的。
     */
    @Test
    public void pangleLeaveConfirmOnlyEverPressesTheAbandonKey() throws Exception {
        Map<String, List<Selector>> m = bundled();
        assertNotNull("要认出这是「确定要退出吗」的二次确认",
                hit(m, Keys.AD_LEAVE_CONFIRM, PANGLE_LEAVE_CONFIRM));
        NodeView abandon = hit(m, Keys.AD_ABANDON, PANGLE_LEAVE_CONFIRM);
        assertNotNull("退出这一支靠「坚持退出」", abandon);
        assertTrue("坚持退出".equals(abandon.text()));
        for (String key : new String[]{Keys.AD_CLAIM, Keys.AD_RESUME, Keys.AD_JUMP}) {
            assertNull(key + " 不能命中「去领取奖励」——那是下载安装那条路",
                    hit(m, key, "去领取奖励"));
        }
    }

    @Test
    public void jumpButtonOnEachCardIsFound() throws Exception {
        Map<String, List<Selector>> m = bundled();
        NodeView pangle = hit(m, Keys.AD_JUMP, PANGLE_PROMO);
        assertNotNull("穿山甲卡上的跳转键是「我要加速」", pangle);
        assertTrue("命中的该是弹卡上那颗，不是被它盖住的「立即下载」，实到的是：" + pangle.text(),
                "我要加速".equals(pangle.text()));
        NodeView gdt = hit(m, Keys.AD_JUMP, GDT_PROMO);
        assertNotNull("优量汇卡上的跳转键是「我要更快拿奖」", gdt);
        assertTrue("我要更快拿奖".equals(gdt.text()));
        NodeView accel = hit(m, Keys.AD_JUMP, PANGLE_PROMO_ACCELERATE);
        assertNotNull("「获得加速机会」那张卡上的键是「我要直接拿奖励」", accel);
        assertTrue("实到的是：" + accel.text(), "我要直接拿奖励".equals(accel.text()));
        NodeView exp = hit(m, Keys.AD_JUMP, PANGLE_PROMO_EXPERIENCE);
        assertNotNull("「去体验」那张卡上的键就叫「去体验」", exp);
        assertTrue("实到的是：" + exp.text(), "去体验".equals(exp.text()));
    }

    /**
     * 「我要直接拿奖励」名字像领奖键，其实是跳转键（卡上自己写着「跳转到详情页或第三方应用」）。
     * 要是 ad_claim／ad_resume 认了它，脚本就会在默认设置（跳转开关关着）下把它当成「让视频
     * 接着播」或者「播完领奖」按下去 —— 那就是在用户没同意的情况下替广告主刷一次付费点击。
     */
    @Test
    public void theFasterRewardButtonCountsOnlyAsAJumpKey() throws Exception {
        Map<String, List<Selector>> m = bundled();
        assertNotNull(hit(m, Keys.AD_JUMP, "我要直接拿奖励"));
        for (String key : new String[]{Keys.AD_CLAIM, Keys.AD_RESUME, Keys.AD_CLOSE,
                Keys.AD_EARNED}) {
            assertNull(key + " 不能命中「我要直接拿奖励」——那是跳转键",
                    hit(m, key, "我要直接拿奖励"));
        }
    }

    /**
     * 弹卡下面压着一张下载卡（实测「立即下载」id=5db6c3，在 DFS 里比弹卡那颗<b>靠前</b>）。
     * 没有 topmost 的话 {@link NodeMatcher#find} 取第一个命中，点下去落在弹卡上什么都不发生 ——
     * 空点还不报错，比「点不动」更难查。这里用真的层级结构验证挑的是最上层那颗。
     */
    @Test
    public void jumpSkipsTheDownloadCardUnderneathThePopup() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode root = FakeNode.node().add(
                // 被盖住的下载卡：真机上它连 isVisibleToUser 都是 false。
                FakeNode.node().add(
                        FakeNode.text("美团").withBounds(275, 1948, 726, 2041).visible(false),
                        FakeNode.text("立即下载").withId("com.sfacg:id/5db6c3")
                                .withBounds(803, 2011, 935, 2055).visible(false)),
                // 盖在上面的促销卡。
                FakeNode.node().add(
                        FakeNode.text("免看此广告").withBounds(561, 894, 866, 971),
                        FakeNode.text("我要加速").withBounds(214, 1367, 866, 1535)));
        NodeMatcher.Hit h = NodeMatcher.find(root, m.get(Keys.AD_JUMP));
        assertNotNull(h);
        assertTrue("要点弹卡上那颗，不能点被盖住的「立即下载」，实到的是：" + h.node.text(),
                "我要加速".equals(h.node.text()));
    }

    /**
     * ad_jump 不许依赖 isVisibleToUser。
     *
     * <p>广告卡是 Lynx／Canvas 自绘的，isVisibleToUser 在这种树上报什么无法离线验证
     * （本机 uiautomator dump 里连这个属性都没有）。万一它对唯一能点的那颗按钮报 false，
     * 用 visibleOnly 就会把它滤掉、于是「认不出跳转键」—— 那是最糟的失败方式，所以只用
     * requireArea（有没有面积）。
     */
    @Test
    public void jumpDoesNotDependOnIsVisibleToUser() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode root = FakeNode.node().add(
                FakeNode.text("我要加速").withBounds(214, 1367, 866, 1535).visible(false));
        NodeMatcher.Hit h = NodeMatcher.find(root, m.get(Keys.AD_JUMP));
        assertNotNull("报 invisible 但有面积的按钮照样要认", h);
        assertTrue("我要加速".equals(h.node.text()));
    }

    /**
     * 按钮长在另一层窗口里也要找得到。
     *
     * <p>实测淘宝那支试玩广告：截图上卡片写满了字，而<b>活动</b>窗口只有 21 个节点
     * （FrameLayout / LinearLayout / WebView）、一个字都没有 —— 只查活动窗口的话
     * 任何选择器都不可能命中，流程就只剩「按跳过」这条把奖励扔掉的死路。
     */
    @Test
    public void jumpIsFoundEvenWhenItLivesInAnotherWindow() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode activeWindow = FakeNode.node().withClass("android.webkit.WebView");
        FakeNode popupWindow = FakeNode.node().add(
                FakeNode.text("获得加速机会！").withBounds(214, 800, 866, 900),
                FakeNode.text("我要直接拿奖励").withBounds(214, 1367, 866, 1535));
        assertNull("单看活动窗口那层是找不到的",
                NodeMatcher.find(activeWindow, m.get(Keys.AD_JUMP)));

        NodeView all = new MultiRoot(java.util.Arrays.<NodeView>asList(activeWindow, popupWindow));
        NodeMatcher.Hit h = NodeMatcher.find(all, m.get(Keys.AD_JUMP));
        assertNotNull(h);
        assertTrue("实到的是：" + h.node.text(), "我要直接拿奖励".equals(h.node.text()));
        assertNotNull("卡也要认得出来", NodeMatcher.find(all, m.get(Keys.AD_PROMO)));
    }

    /**
     * 零面积的占位节点（Lynx 树里一堆）不许优先于有面积的那颗；但<b>整屏只剩它</b>的时候
     * 还是要认。
     *
     * <p>后半句是 2026-08-23 实测之后翻过来的：那一趟第一支广告的卡上，从按钮那段文字往上八层
     * 祖先全是零面积（日志里 {@code bounds=[0,111][0,111]}），旧写法「宁可认不出也不空点」的结果
     * 就是这一支彻底没有可点的目标、只能放弃 —— 而放弃等于把奖励扔掉。现在第二条候选不带
     * requireArea：认出它之后，点击那一步会向上借最近一个有面积的祖先矩形，连祖先也没有时
     * 再退到「点屏幕正中」。有面积的那颗依然永远优先（第一条候选）。
     */
    @Test
    public void zeroAreaPlaceholderIsNeverPreferredButStillUsableAlone() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode root = FakeNode.node().add(
                FakeNode.text("立即下载").withBounds(0, 111, 1080, 111),
                FakeNode.text("我要加速").withBounds(214, 1367, 866, 1535));
        NodeMatcher.Hit h = NodeMatcher.find(root, m.get(Keys.AD_JUMP));
        assertNotNull(h);
        assertTrue("零高度那颗不算，实到的是：" + h.node.text(), "我要加速".equals(h.node.text()));

        FakeNode onlyPlaceholder = FakeNode.node().add(
                FakeNode.text("我要加速").withBounds(214, 1367, 214, 1367));
        NodeMatcher.Hit lone = NodeMatcher.find(onlyPlaceholder, m.get(Keys.AD_JUMP));
        assertNotNull("整屏只剩零面积那颗时也要认出来（点击那一步会向上借祖先的矩形）", lone);
        assertTrue("我要加速".equals(lone.node.text()));
    }

    /**
     * 顶栏那句「去体验15秒可立即领奖丨跳过」正常播放时也一直挂在那儿。要是它能命中 ad_promo，
     * 脚本会把好好播着的广告误判成「被停住了」，进而去按跳转键 —— 那就变成替广告主刷点击了。
     */
    @Test
    public void topBarAloneIsNotAPromoCard() throws Exception {
        Map<String, List<Selector>> m = bundled();
        assertNull(hit(m, Keys.AD_PROMO, "去体验15秒可立即领奖", "跳过", "反馈", "广告", "立即下载"));
    }

    /** 播放页底部常驻的「立即下载」不该被当成奖励到账，也不该让「已签到」那类文案误命中。 */
    @Test
    public void signPageRewardTextIsNotAnAdReward() throws Exception {
        Map<String, List<Selector>> m = bundled();
        assertNull("签到成功页的「奖励已发放到账号」不是广告奖励",
                hit(m, Keys.AD_EARNED, "签到成功", "奖励已发放到账号", "+5代券"));
        assertNotNull("浏览落地页回来后这句才算真的到手",
                hit(m, Keys.AD_EARNED, "已完成浏览10秒，提前获得奖励"));
    }

    /** 卡上写了要浏览几秒就照它停，读不出来才用默认值。 */
    @Test
    public void dwellSecondsAreReadFromTheCard() throws Exception {
        Map<String, List<Selector>> m = bundled();
        NodeView pangle = hit(m, Keys.AD_DWELL_HINT, PANGLE_PROMO);
        assertNotNull(pangle);
        assertTrue("要认「去体验15秒」那句，不能认成「可提前13秒领奖」的 13",
                15 == com.example.blb.util.Texts.firstInt(pangle.text(), -1));
        NodeView gdt = hit(m, Keys.AD_DWELL_HINT, GDT_PROMO);
        assertNotNull(gdt);
        assertTrue(10 == com.example.blb.util.Texts.firstInt(gdt.text(), -1));
        assertNull("「可提前13秒领奖」单独出现时不算浏览时长",
                hit(m, Keys.AD_DWELL_HINT, "可提前13秒领奖"));
        // 「获得加速机会」那张卡上写的是「去看15秒可直接拿奖励」，这一句不在 ad_dwell_hint 里；
        // 秒数是从顶栏那句读到的 —— 顶栏那条正则因此不能删。
        NodeView accel = hit(m, Keys.AD_DWELL_HINT, PANGLE_PROMO_ACCELERATE);
        assertNotNull("秒数得从顶栏那句「去体验15秒可立即领奖」读到", accel);
        assertTrue(15 == com.example.blb.util.Texts.firstInt(accel.text(), -1));
        // 「去体验」那张卡被 Lynx 拆成三个节点，整句在卡上匹配不到；这时候读不出秒数，
        // AdWatchTask 退回默认停留时长。钉在这儿是为了说明它是「已知会退默认值」，不是漏配。
        assertNull("拆开的「去体验」「9秒」「可立即领奖」读不出整句",
                hit(m, Keys.AD_DWELL_HINT, PANGLE_PROMO_EXPERIENCE));
    }

    /**
     * 「跳过」只许出现在 ad_skip 这一组里 —— 它唯一的用途是放弃一支已经拿不到奖励的广告，
     * 而且只在播放页连返回键都按不动的时候用。任何会在正常播放中被按下去的键都不能命中它。
     */
    @Test
    public void skipIsOnlyEverTheAbandonKey() throws Exception {
        Map<String, List<Selector>> m = bundled();
        for (String key : new String[]{Keys.AD_JUMP, Keys.AD_RESUME, Keys.AD_CLAIM, Keys.AD_CLOSE}) {
            assertNull(key + " 不能命中「跳过」", hit(m, key, "跳过"));
            assertNull(key + " 不能命中「去体验15秒可立即领奖」",
                    hit(m, key, "去体验15秒可立即领奖"));
        }
        assertFalse(m.get(Keys.AD_JUMP).isEmpty());
        assertNotNull("穿山甲顶栏那颗「跳过」要认得出来", hit(m, Keys.AD_SKIP, PANGLE_PROMO));
        assertNull("整句顶栏不算「跳过」键", hit(m, Keys.AD_SKIP, "去体验15秒可立即领奖丨跳过"));
    }
}
