package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.util.Texts;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 花钱那一屏的护栏，按 2026-08-24 12:58–13:05 真机校准的「选择章节」页
 * （{@code com.sf.ui.novel.reader.download.ReaderDownloadActivity}）造树钉住。
 *
 * <p>这一屏是整个 App 里唯一会真的花掉用户券的地方，而用户的硬约束是「只能用代券，
 * 我不会充值火券」。所以这里的每一条都不是风格问题：
 * <ul>
 *   <li>「全选」和「去充值」永远只读不点 —— 「订阅全部」一次就能把整本书的火券花光；</li>
 *   <li>判据是底部那句「实付」里火券为 0，余额那一行和标价那一行都不算；</li>
 *   <li>「已选」必须正好 1 章，勾中卷标题＝勾中整卷；</li>
 *   <li>券不够时菠萝包自己把「立即下载」换成「去充值」，那时候连按都按不着。</li>
 * </ul>
 */
public class ChapterBuyGuardTest {

    private static Map<String, List<Selector>> bundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json", f.isFile());
        return SelectorSet.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    /** 按 key 的候选链找，第一条有结果的就是答案 —— 和 StepRunner.findAllOn 同一套规则。 */
    private static List<NodeView> findAll(Map<String, List<Selector>> sel, String key, NodeView root) {
        List<Selector> candidates = sel.get(key);
        assertNotNull("selectors.json 里缺 " + key, candidates);
        for (Selector s : candidates) {
            List<NodeView> hits = NodeMatcher.findAll(root, s);
            if (!hits.isEmpty()) return hits;
        }
        return new ArrayList<>();
    }

    /** 单个节点走 NodeMatcher.find —— 和 StepRunner.findAny 一样，会应用 clickableAncestor。 */
    private static NodeView findOne(Map<String, List<Selector>> sel, String key, NodeView root) {
        List<Selector> candidates = sel.get(key);
        assertNotNull("selectors.json 里缺 " + key, candidates);
        NodeMatcher.Hit hit = NodeMatcher.find(root, candidates);
        return hit == null ? null : hit.node;
    }

    // ---------- 造树 ----------

    /**
     * 一行章节。三个标记严格照真机来（2026-08-24 21:21 真买后的 PROBE_CHAPTER 树 + 21:57 截图）：
     *
     * <ul>
     *   <li>{@code lock}＝{@code title_lock} 在不在。付费章<b>买完之后锁还在</b>
     *       （只是闭锁图标变开锁图标，树里同为空文本节点）—— 所以它只说明「这是付费章」。</li>
     *   <li>{@code downloaded}＝写着「已下载」（{@code title_check}），买完才出现。</li>
     *   <li>右边那个圆圈 {@code item_cb} 和「已下载」<b>互斥</b>：已下载的行右边是三个字，
     *       没有圆圈，所以点它「已选」还是 0 章。这一条以前造错了（给每行都加圆圈），
     *       于是测试永远看不到「买过的行点不动」这件事。</li>
     * </ul>
     */
    private static FakeNode chapterRow(String title, int top, boolean lock, boolean downloaded) {
        FakeNode row = FakeNode.node().withClass("android.widget.RelativeLayout")
                .clickable(true).withBounds(0, top, 1080, top + 140);
        row.add(FakeNode.text(title).withId("com.sfacg:id/title").clickable(false)
                .withBounds(60, top + 20, 800, top + 90));
        if (lock) {
            row.add(FakeNode.node().withId("com.sfacg:id/title_lock")
                    .withBounds(60, top + 95, 100, top + 130));
        }
        FakeNode right = FakeNode.node().withId("com.sfacg:id/layoutRight")
                .withBounds(900, top, 1080, top + 140);
        if (downloaded) {
            row.add(FakeNode.text("已下载").withId("com.sfacg:id/title_check")
                    .withBounds(60, top + 95, 200, top + 130));
        } else {
            right.add(FakeNode.node().withId("com.sfacg:id/item_cb").clickable(false)
                    .withBounds(940, top + 40, 1020, top + 110));
        }
        row.add(right);
        return row;
    }

    /** 卷标题行：也用 title 这个 id、也带 item_cb（勾它＝勾整卷），只是行首没有标号。 */
    private static FakeNode volumeRow(String title, int top) {
        return FakeNode.node().withClass("android.widget.RelativeLayout").clickable(true)
                .withBounds(0, top, 1080, top + 120)
                .add(FakeNode.text(title).withId("com.sfacg:id/title").clickable(false)
                                .withBounds(60, top + 20, 800, top + 90),
                        FakeNode.node().withId("com.sfacg:id/item_cb").clickable(false)
                                .withBounds(940, top + 25, 1020, top + 95));
    }

    /**
     * 勾了一章、而且这一章只花代券时的整屏。
     *
     * @param payText 底部 {@code tvTips} 那句「实付…」，传 null 表示还没出现
     */
    private static FakeNode pickerPage(String selectedText, String payText) {
        FakeNode page = FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("选择章节").withBounds(400, 100, 680, 170),
                // 右上角这两颗永远只读不点。
                FakeNode.text("全选").withId("com.sfacg:id/selected_all").clickable(true)
                        .withBounds(900, 100, 1040, 170));
        FakeNode list = FakeNode.node().withId("com.sfacg:id/downloadRecycler")
                .withClass("androidx.recyclerview.widget.RecyclerView")
                .scrollable(true).withBounds(0, 200, 1080, 2000);
        list.add(volumeRow("正文", 210),
                // 免费章、已经下载过
                chapterRow("1   开学第一天", 340, false, true),
                // 付费章、这个号还没买 —— 唯一该花券的那种
                chapterRow("2   久违的笑", 490, true, false),
                // 付费章、已经买到（锁还在，只是变成开锁；右边写着「已下载」）
                chapterRow("3   周日工作", 640, true, true),
                // 免费章、还没下载（右边有圆圈，但不用花券）
                chapterRow("4   放学路上", 790, false, false));
        page.add(list);
        page.add(FakeNode.node().withId("com.sfacg:id/goto_top").clickable(true)
                .withBounds(960, 1800, 1050, 1900));

        FakeNode footer = FakeNode.node().withBounds(0, 2050, 1080, 2400);
        footer.add(FakeNode.text(selectedText).withId("com.sfacg:id/tvSelect")
                        .withBounds(60, 2070, 400, 2130),
                FakeNode.text("需 12 火券").withBounds(420, 2070, 700, 2130),
                FakeNode.text("账户余额：0火券/15代券").withId("com.sfacg:id/tvAccount")
                        .withBounds(60, 2140, 700, 2200));
        if (payText != null) {
            footer.add(FakeNode.text(payText).withId("com.sfacg:id/tvTips")
                    .withBounds(60, 2210, 700, 2270));
        }
        footer.add(FakeNode.node().withId("com.sfacg:id/img_down").clickable(true)
                .withBounds(760, 2140, 1040, 2270)
                .add(FakeNode.text("立即下载").withId("com.sfacg:id/tv_download").clickable(false)
                        .withBounds(800, 2180, 1000, 2240)));
        page.add(footer);
        return page;
    }

    /** 代券不够时菠萝包自己把底部换掉：「立即下载」直接消失，只剩「去充值」。 */
    private static FakeNode brokePage() {
        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("选择章节").withBounds(400, 100, 680, 170),
                FakeNode.node().withBounds(0, 2050, 1080, 2400).add(
                        FakeNode.text("已选 1 章").withId("com.sfacg:id/tvSelect")
                                .withBounds(60, 2070, 400, 2130),
                        FakeNode.text("账户余额：0火券/15代券").withId("com.sfacg:id/tvAccount")
                                .withBounds(60, 2140, 700, 2200),
                        FakeNode.text("火券不足，请充值").withId("com.sfacg:id/tvChargeTips")
                                .withBounds(60, 2210, 700, 2270),
                        FakeNode.text("去充值").withId("com.sfacg:id/tvCharge").clickable(true)
                                .withBounds(760, 2200, 1040, 2280)));
    }

    // ---------- 行与卷 ----------

    /** 章节行和卷标题行用同一个 id，所以选择器一定会把卷标题也捞上来 —— 靠标号把它剔掉。 */
    @Test
    public void volumeHeaderIsMatchedByTheRowSelectorAndMustBeFilteredByNumber() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        List<NodeView> titles = findAll(sel, Keys.CHAPTER_ROW_TITLE, pickerPage("已选 1 章", null));
        assertEquals("卷标题行也用 title 这个 id", 5, titles.size());

        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (NodeView t : titles) {
            if (Texts.rowChapterNo(t.text()) < 0) dropped.add(t.text());
            else kept.add(t.text());
        }
        assertEquals(4, kept.size());
        assertEquals(1, dropped.size());
        assertEquals("正文", dropped.get(0));
    }

    /** 行内的三个标记读出来是什么 —— 和 {@link ChapterRowState#read} 用的是同一套选择器。 */
    private static ChapterRowState stateOf(Map<String, List<Selector>> sel, NodeView row) {
        return ChapterRowState.of(
                !findAll(sel, Keys.CHAPTER_LOCKED, row).isEmpty(),
                !findAll(sel, Keys.CHAPTER_OWNED, row).isEmpty(),
                !findAll(sel, Keys.CHAPTER_SELECTABLE, row).isEmpty());
    }

    /**
     * 「这一行要不要花券、能不能记账」的判据，逐行钉住。
     *
     * <p>两次真实事故各贡献了一条：
     * <ul>
     *   <li>2026-08-24（40 代券）：判据曾是「锁没了＝买到了」。真机上锁买完<b>不会没</b>
     *       （闭锁变开锁，树里同为空文本节点），于是券扣了、判成失败、账本一条没记。</li>
     *   <li>2026-08-25（第49章挂两个号）：判据曾是「有『已下载』＝这个号买过」。而「已下载」
     *       是<b>本机</b>的下载状态、8 个号共用 —— 皓平买完下载到这台手机，换五杯半雪碧登录
     *       那一行照样写着「已下载」，于是凭共用文件编造了第二个订阅者；2026-09-14 允许
     *       明细证实的跨号历史，也不允许从下载状态猜买家。</li>
     * </ul>
     * 所以现在只剩两条结论：{@code free()}（没有锁＝免费章，唯一能跨号推断的）和
     * {@code buyable()}（有锁 + 还有勾选圈＝这个号要花券买它）。付费章归谁只认真实购买记录。
     *
     * <p>还必须在<b>行内</b>查：整屏上到处都是别的行的锁和「已下载」。
     */
    @Test
    public void ownershipIsNeverInferredFromDownloaded() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        List<NodeView> titles = findAll(sel, Keys.CHAPTER_ROW_TITLE, pickerPage("已选 1 章", null));

        ChapterRowState freeDone = stateOf(sel, CatalogScanner.rowOf(titles.get(1)));
        assertTrue(freeDone.describe(), freeDone.free());
        assertFalse("免费章不用花券", freeDone.buyable());
        assertFalse("写着「已下载」的行右边没有圆圈", freeDone.selectable);

        ChapterRowState toBuy = stateOf(sel, CatalogScanner.rowOf(titles.get(2)));
        assertTrue(toBuy.describe(), toBuy.buyable());
        assertFalse("付费章不是免费章", toBuy.free());
        assertFalse(toBuy.downloaded);
        assertTrue("还没买的行才有勾选圈", toBuy.selectable);

        ChapterRowState downloaded = stateOf(sel, CatalogScanner.rowOf(titles.get(3)));
        assertTrue("有锁 + 已下载：本机有这一章，但看不出是哪个号买的", downloaded.deviceHasIt());
        assertFalse("绝不能当成免费章回填给当前这个号", downloaded.free());
        assertFalse("也买不了：没有勾选圈，点下去「已选」还是 0 章", downloaded.buyable());
        assertTrue("买完锁不会消失（只是变成开锁）", downloaded.lock);

        ChapterRowState freeTodo = stateOf(sel, CatalogScanner.rowOf(titles.get(4)));
        assertTrue("没有锁就是免费章，谁登录都看得到", freeTodo.free());
        assertFalse("免费章不用花券", freeTodo.buyable());
        assertTrue("免费未下载的行有圆圈 —— 有圆圈不等于要花券", freeTodo.selectable);
    }

    /** 行标题 clickable=false，点它没反应；必须点整行那个 RelativeLayout。 */
    @Test
    public void theClickableThingIsTheWholeRow() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        NodeView title = findAll(sel, Keys.CHAPTER_ROW_TITLE, pickerPage("已选 1 章", null)).get(1);
        assertFalse(title.clickable());
        NodeView row = CatalogScanner.rowOf(title);
        assertTrue("要点的是整行", row.clickable());
        assertEquals("android.widget.RelativeLayout", row.className());
    }

    // ---------- 「已选」必须正好 1 章 ----------

    @Test
    public void selectedCountMustBeExactlyOne() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        NodeView one = findOne(sel, Keys.SELECTED_COUNT, pickerPage("已选 1 章", "实付0火券+12代券"));
        assertNotNull(one);
        assertEquals(1, Texts.parseCount(one.text()));

        // 勾中了卷标题＝勾中整卷，「已选」会是一大串数字，这时候绝不许按「立即下载」。
        NodeView many = findOne(sel, Keys.SELECTED_COUNT, pickerPage("已选 37 章", "实付0火券+444代券"));
        assertNotNull(many);
        assertEquals(37, Texts.parseCount(many.text()));

        // 勾选没生效：一章都没选上，同样不许往下走。
        NodeView none = findOne(sel, Keys.SELECTED_COUNT, pickerPage("已选 0 章", null));
        assertNotNull(none);
        assertEquals(0, Texts.parseCount(none.text()));
    }

    // ---------- 判据只看「实付」 ----------

    @Test
    public void onlyThePayLineDecidesWhetherWeCanAfford() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        NodeView pay = findOne(sel, Keys.PAY_DETAIL, pickerPage("已选 1 章", "实付0火券+12代券"));
        assertNotNull(pay);
        assertEquals("实付0火券+12代券", pay.text());
        assertTrue(Texts.parsePayment(pay.text()).vouchersOnly());
    }

    /** 实付里带火券 → 放弃这一章、换下一个号，绝不因为「代券余额够」就下手。 */
    @Test
    public void aPayLineWithFireCoinsIsRefusedEvenWhenVouchersLookEnough() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        FakeNode page = pickerPage("已选 1 章", "实付5火券+15代券");
        // 余额那一行看着刚好够：代券 15，要付的代券也是 15。
        Texts.Balance balance = Texts.parseBalance("账户余额：0火券/15代券");
        assertEquals(15, balance.voucher);
        NodeView pay = findOne(sel, Keys.PAY_DETAIL, page);
        assertNotNull(pay);
        assertFalse("实付里有 5 火券，必须放弃", Texts.parsePayment(pay.text()).vouchersOnly());
    }

    /** 「实付」还没出现时读不到，而读不到一律当买不起 —— 不许拿标价或余额顶上。 */
    @Test
    public void aMissingPayLineCountsAsCannotAfford() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        FakeNode page = pickerPage("已选 1 章", null);
        assertNull("「需 12 火券」是标价，不是实付", findOne(sel, Keys.PAY_DETAIL, page));
        assertFalse(Texts.parsePayment(null).vouchersOnly());
        // 标价那一行仍然读得到，只是只能写日志用。
        NodeView price = findOne(sel, Keys.PRICE_HINT, page);
        assertNotNull(price);
        assertEquals("需 12 火券", price.text());
    }

    // ---------- 「全选」「去充值」只读不点 ----------

    /** 要按的是「立即下载」，而它的可点祖先是 img_down；「全选」绝不能被这条选择器命中。 */
    @Test
    public void theBuyButtonIsDownloadNotSelectAll() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        NodeView button = findOne(sel, Keys.SUBSCRIBE_BUTTON, pickerPage("已选 1 章", "实付0火券+12代券"));
        assertNotNull(button);
        assertTrue("按下去的必须是能点的那一层", button.clickable());
        assertEquals("com.sfacg:id/img_down", button.viewId());
    }

    /** 券不够时「立即下载」整个消失，剩下的是「去充值」—— 物理上不可能误买。 */
    @Test
    public void whenBrokeThereIsNoBuyButtonAtAll() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        FakeNode broke = brokePage();
        assertNull("「立即下载」不在了", findOne(sel, Keys.SUBSCRIBE_BUTTON, broke));
        NodeView charge = findOne(sel, Keys.INSUFFICIENT_COUPONS, broke);
        assertNotNull("要认出这是「券不够」那一屏", charge);
    }
}
