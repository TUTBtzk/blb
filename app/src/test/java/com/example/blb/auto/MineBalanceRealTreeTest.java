package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.blb.util.Texts;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * 把真机上量到的余额那一行钉在这儿。
 *
 * <p>「我的」页那三列（火券／金币／代券）的数字和标签<b>都没有 resource-id</b>，认哪个数字
 * 属于哪个标签完全靠 bounds 的位置关系，是这套选择器里最脆的一处 —— 所以用 2026-08-23
 * 实测的坐标造树，钉住「0 是火券、10 是代券，中间那个 152 是金币，谁都别串味」。
 *
 * <p>用户 2026-08-23 报的「所有账号都无法识别有多少代券」就是这一行读不出来。
 */
public class MineBalanceRealTreeTest {

    private static Map<String, List<Selector>> bundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json", f.isFile());
        return SelectorSet.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    private static Texts.Balance read(FakeNode root) throws Exception {
        Map<String, List<Selector>> m = bundled();
        return BalanceReader.read(root, m::get);
    }

    /** 「我的」页，按 mine.xml（2026-08-23，登录的是「好好上课是」）的真实 bounds 摆。 */
    private static FakeNode minePage() {
        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("好好上课是").withId("com.sfacg:id/nickname")
                        .withBounds(298, 194, 553, 263),
                FakeNode.text("我的帐户").withBounds(77, 614, 213, 660),
                FakeNode.text("我的钱包").withBounds(826, 614, 962, 660),
                FakeNode.text("0").withBounds(63, 687, 307, 745),
                FakeNode.text("火券").withBounds(63, 752, 307, 798),
                FakeNode.text("152").withBounds(307, 687, 552, 745),
                FakeNode.text("金币").withBounds(307, 752, 552, 798),
                FakeNode.text("10").withBounds(552, 687, 797, 745),
                FakeNode.text("代券").withBounds(552, 752, 797, 798),
                FakeNode.text("每日免费领取代券").withId("com.sfacg:id/tvContent")
                        .withBounds(269, 955, 541, 1001));
    }

    @Test
    public void readsBothCurrenciesFromMinePage() throws Exception {
        Texts.Balance b = read(minePage());
        assertEquals("火券应当读成 0", 0, b.fire);
        assertEquals("代券应当读成 10", 10, b.voucher);
    }

    /** 中间那一列是金币，1520 券的错觉就是从「读错列」来的。 */
    @Test
    public void doesNotMistakeCoinsForCoupons() throws Exception {
        Texts.Balance b = read(minePage());
        assertTrue("152 是金币，不该出现在火券或代券上", b.fire != 152 && b.voucher != 152);
    }

    /** 标签下方的数字不算：只认正上方那个。 */
    @Test
    public void ignoresNumbersBelowTheLabel() {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("代券").withBounds(552, 752, 797, 798),
                FakeNode.text("77").withBounds(552, 800, 797, 860));
        NodeView label = NodeMatcher.find(root,
                java.util.Collections.singletonList(exactText("代券"))).node;
        assertEquals(-1, NodeMatcher.countAbove(root, label));
    }

    /** 隔着大半屏的数字不算（不然会把顶栏的数字当余额）。 */
    @Test
    public void ignoresNumbersTooFarAway() {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("999").withBounds(552, 100, 797, 160),
                FakeNode.text("代券").withBounds(552, 752, 797, 798));
        NodeView label = NodeMatcher.find(root,
                java.util.Collections.singletonList(exactText("代券"))).node;
        assertEquals(-1, NodeMatcher.countAbove(root, label));
    }

    /** 零面积的占位数字不算（Lynx／自绘树里到处是这种节点）。 */
    @Test
    public void ignoresZeroAreaNumbers() {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("42").withBounds(552, 745, 552, 745),
                FakeNode.text("代券").withBounds(552, 752, 797, 798));
        NodeView label = NodeMatcher.find(root,
                java.util.Collections.singletonList(exactText("代券"))).node;
        assertEquals(-1, NodeMatcher.countAbove(root, label));
    }

    /** 我的钱包页两颗数字各有 id，走的是 id 那条路，别退化到位置猜。 */
    @Test
    public void readsWalletPageById() throws Exception {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("我的火券").withId("com.sfacg:id/tv_myWallet")
                        .withBounds(223, 349, 359, 395),
                FakeNode.text("3").withId("com.sfacg:id/tv_friemoney")
                        .withBounds(260, 452, 321, 582),
                FakeNode.text("我的代券").withId("com.sfacg:id/tv_voucher_tips")
                        .withBounds(639, 349, 775, 395),
                FakeNode.text("8").withId("com.sfacg:id/tv_voucher")
                        .withBounds(758, 452, 819, 582),
                FakeNode.text("金币").withBounds(219, 934, 321, 1003),
                FakeNode.text("2092").withBounds(361, 934, 483, 1003));
        Texts.Balance b = read(root);
        assertEquals(3, b.fire);
        assertEquals(8, b.voucher);
    }

    /** 批量购买页那一行两种券在同一段文字里，仍旧优先走它。 */
    @Test
    public void readsBatchPageLine() throws Exception {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("账户余额：12火券/34代券").withId("com.sfacg:id/tvAccount")
                        .withBounds(43, 2302, 582, 2348));
        Texts.Balance b = read(root);
        assertEquals(12, b.fire);
        assertEquals(34, b.voucher);
    }

    /**
     * 签到面板上一个余额都没有 —— 而且「免费领3代券」这种文案绝不许被当成余额 3。
     * 这是原来那句「所有账号都无法识别有多少代券」背后最危险的邻居。
     */
    @Test
    public void signPanelHasNoBalance() throws Exception {
        FakeNode root = FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("0").withId("com.sfacg:id/tvSignDay").withBounds(224, 645, 287, 777),
                FakeNode.text("天").withId("com.sfacg:id/tvSignDayUnit")
                        .withBounds(301, 714, 335, 759),
                FakeNode.text("7天连签").withId("com.sfacg:id/tvSignGift")
                        .withBounds(770, 1131, 893, 1176),
                FakeNode.text("看小视频再领代券").withId("com.sfacg:id/tvAdTitleTips")
                        .withBounds(300, 1440, 620, 1493),
                FakeNode.text("免费领3代券").withId("com.sfacg:id/sign_in_ad_goto")
                        .withBounds(658, 1446, 923, 1539));
        Texts.Balance b = read(root);
        assertEquals(-1, b.fire);
        assertEquals(-1, b.voucher);
    }

    private static Selector exactText(String text) {
        Selector s = new Selector();
        s.text = text;
        s.requireArea = true;
        return s;
    }
}
