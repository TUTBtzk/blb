package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.PurchaseRow;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 「订阅明细」页的解析与<b>逐章</b>对账，按 2026-08-25 真机 dump 造树钉住。
 *
 * <p>为什么逐章这一层非要有：用户那两条硬约束都是逐章的 —— 「每一章只能有一个账号订阅」、
 * 「8 个号最后能拼出完整一本」。清单那一层是按书聚合的，只答得出「总数差了几章」，
 * 答不出差在哪一章；而 2026-08-25 第49章被误挂成两个号那件事，只有逐章比对才能指着说是第49章。
 *
 * <p>入口也是用户 2026-08-25 纠正的：要点清单里那<b>一整行</b>（他圈的行右上角那个「&gt;」
 * 实测是空文本、没 id、不可点的装饰），<b>不是</b>「查看目录」——「查看目录」进去是整本书的
 * 目录列表，不是买过哪几章。
 */
public class SubscribedDetailTest {

    private static final String BOOK = "发小竟然是后悔文男主";
    private static final String WHO = "五杯半雪碧";
    private static final long ME = 7L;
    private static final long OTHER = 9L;
    private static final long NOW = 1_756_000_000_000L;

    /** 2026-08-25 实测原文：卷名 + 空格 + 章号 + 空格 + 章标题。 */
    private static final String ROW_50 = "世界线的变动，学生会长的恋爱 50 订婚事宜，梦玲失踪";
    private static final String ROW_48 = "世界线的变动，学生会长的恋爱 48 滤镜破碎，总裁秘书";
    /** 2026-08-31 实测原文：章号和章标题<b>之间没有空格</b>（就是这条停了整趟订阅）。 */
    private static final String ROW_71 = "世界线的变动，学生会长的恋爱  71留宿之夜，夏优来访";

    private static Map<String, List<Selector>> bundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json", f.isFile());
        return SelectorSet.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    // ---------- 解析一条 ----------

    @Test
    public void parsesTheRealDetailRow() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow(ROW_50, "20", "代券", "2026-08-25");
        assertTrue(e.describe(), e.known());
        assertEquals(50, e.chapterNo);
        assertEquals("世界线的变动，学生会长的恋爱", e.volume);
        assertEquals("订婚事宜，梦玲失踪", e.title);
        assertEquals(20, e.amount);
        assertEquals("代券", e.currency);
        assertEquals("2026-08-25", e.date);
        assertFalse("一分火券都没花", e.fireSpent());
    }

    @Test
    public void parsesTheOtherRealDetailRow() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow(ROW_48, "20", "代券", "2026-08-24");
        assertEquals(48, e.chapterNo);
        assertEquals("滤镜破碎，总裁秘书", e.title);
    }

    /** 没有卷名的书（章号就在最前面）也得认得出来。 */
    @Test
    public void aRowWithoutAVolumeStillParses() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow("12 周日工作", "20", "代券", null);
        assertEquals(12, e.chapterNo);
        assertEquals("", e.volume);
        assertEquals("周日工作", e.title);
    }

    /**
     * 章号后面那个空格<b>不保证有</b>：2026-08-31 实测第71章那一条粘在一起。
     *
     * <p>这一条不是「解析得更漂亮一点」，它把整趟订阅停掉过：章号认不出 → 逐章对账认为
     * 明细里没有第71章 → 账本里 wefeef 名下那条（20 代券，真买的）被判成「误挂、其实没人买」
     * → 中止，从那以后一章都买不了。
     */
    @Test
    public void aChapterNumberGluedToItsTitleIsStillRecognised() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow(ROW_71, "20", "代券", "2026-08-30");
        assertTrue(e.describe(), e.known());
        assertEquals(71, e.chapterNo);
        assertEquals("世界线的变动，学生会长的恋爱", e.volume);
        assertEquals("留宿之夜，夏优来访", e.title);
        assertEquals(20, e.amount);
    }

    @Test
    public void gluedNumbersWorkWithoutAVolumeToo() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow("71留宿之夜，夏优来访", "20", "代券", null);
        assertEquals(71, e.chapterNo);
        assertEquals("", e.volume);
        assertEquals("留宿之夜，夏优来访", e.title);
    }

    /** 有空格的那种照旧走原来那条路 —— 卷名里万一带数字也不该抢在纯数字节前面。 */
    @Test
    public void aSpacedNumberStillWinsOverAGluedOne() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow(
                "2023年的番外 50 订婚事宜", "20", "代券", null);
        assertEquals(50, e.chapterNo);
        assertEquals("2023年的番外", e.volume);
        assertEquals("订婚事宜", e.title);
    }

    /**
     * 认不出章号就说认不出，<b>绝不</b>猜一个出来。
     *
     * <p>猜错会把「漏记」和「误挂」判反 —— 那两件事的处置正好相反（一个是别的号会再买同一章、
     * 一个是这一章永远没人买），判反比不判更糟。
     */
    @Test
    public void anUnparsableRowStaysUnknown() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow("作品相关", "20", "代券", null);
        assertFalse(e.known());
        assertEquals(-1, e.chapterNo);
        assertEquals("原文要留着让人看", "作品相关", e.raw);
        assertFalse(SubscribedDetail.parseRow(null, null, null, null).known());
        assertEquals("花费读不到就是 -1，不是 0",
                -1, SubscribedDetail.parseRow(ROW_50, null, null, null).amount);
        assertEquals("日期不是金额", -1,
                SubscribedDetail.parseRow(ROW_50, "2026-08-25", "代券", null).amount);
    }

    // ---------- 逐章对账 ----------

    private static PurchaseRow row(long accountId, int chapterNo, String title,
                                   int vouchers, int fire, long at, String who) {
        PurchaseRow p = new PurchaseRow();
        p.accountId = accountId;
        p.chapterNo = chapterNo;
        p.chapterTitle = title;
        p.costVouchers = vouchers;
        p.costCoupons = fire;
        p.purchasedAt = at;
        p.source = "AUTO";
        p.accountNickname = who;
        return p;
    }

    private static PurchaseRow mine(int chapterNo, String title) {
        return row(ME, chapterNo, title, 20, 0, NOW - 86_400_000L, WHO);
    }

    private static SubscribedDetail.Entry ui(String desc, String amount) {
        return SubscribedDetail.parseRow(desc, amount, "代券", "2026-08-25");
    }

    private static VoucherLedger.Audit audit(List<SubscribedDetail.Entry> ui,
                                             List<PurchaseRow> ledger) {
        return SubscribedDetail.reconcile(WHO, ME, BOOK, ui, ledger, NOW);
    }

    /** 2026-08-25 真机上的现场：明细两条（第50、48章各 20 代券），账本两条 AUTO，逐章对上了。 */
    @Test
    public void matchingChaptersAreClean() {
        VoucherLedger.Audit a = audit(
                Arrays.asList(ui(ROW_50, "20"), ui(ROW_48, "20")),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪"), mine(48, "48   滤镜破碎，总裁秘书")));
        assertTrue(a.message, a.ok);
        assertTrue("这是一次真的核对", a.checked);
        assertTrue(a.message, a.message.contains("第48章"));
        assertTrue(a.message, a.message.contains("第50章"));
    }

    /**
     * 账本里的标题是目录行原文（带行首标号「50   订婚事宜…」），明细里没有标号 ——
     * 只差标号不算对不上（号会变、名字不会）。
     */
    @Test
    public void aLeadingChapterNumberInTheLedgerTitleStillMatches() {
        assertTrue(SubscribedDetail.sameChapter("50   订婚事宜，梦玲失踪", "订婚事宜，梦玲失踪"));
        assertTrue("没登记标题谈不上对不上", SubscribedDetail.sameChapter(null, "订婚事宜"));
        assertFalse(SubscribedDetail.sameChapter("50   周日工作", "订婚事宜，梦玲失踪"));
    }

    /**
     * 菠萝包说买过、账本一条都没有＝券扣了没记账（2026-08-24 那次 40 代券）。
     * 不停下的话，下一趟会有第二个号再买同一章。
     */
    @Test
    public void aChapterMissingFromTheLedgerStopsTheRun() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                new ArrayList<PurchaseRow>());
        assertFalse(a.message, a.ok);
        assertTrue(a.checked);
        assertTrue(a.message, a.message.contains("没记账"));
        assertTrue(a.message, a.message.contains("第50章"));
    }

    /**
     * 菠萝包说这一章是这个号买的、账本却记在<b>另一个号</b>名下 —— 这就是「一章两个号」，
     * 2026-08-25 第49章那件事的形状。必须指名道姓地报出来。
     */
    @Test
    public void aChapterOwnedByAnotherAccountInTheLedgerStopsTheRun() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(row(OTHER, 50, "50   订婚事宜，梦玲失踪", 20, 0,
                        NOW - 86_400_000L, "皓平")));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("皓平"));
        assertTrue(a.message, a.message.contains("第50章"));
    }

    /**
     * 账本挂着、明细里没有，而且不是刚买的＝这一章被误挂给了这个号。
     * 它其实没人买，却会被 {@code findNextUnownedChapter} 永远跳过 —— 那就是漏订。
     */
    @Test
    public void aStaleLedgerEntryMissingFromTheListStopsTheRun() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪"),
                        mine(49, "49   夜色，海边")));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("第49章"));
        assertTrue(a.message, a.message.contains("漏订"));
    }

    /**
     * 2026-08-31 真机上的那一停：账本里 wefeef 名下第71章是真买的（20 代券），明细里也有，
     * 只是那一条的章号粘着标题（「71留宿之夜」）没被认出来，于是被判成「漏订」中止整趟。
     * 认出来之后这一趟必须是干净的。
     */
    @Test
    public void theGluedChapterNumberNoLongerLooksLikeAMissedChapter() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_71, "20")),
                Arrays.asList(mine(71, "71   留宿之夜，夏优来访")));
        assertTrue(a.message, a.ok);
        assertTrue("这是一次真的核对", a.checked);
        assertTrue(a.message, a.message.contains("第71章"));
    }

    /**
     * 真要中止的时候，那句话得自带「有几条读不出章号」这个线索 ——
     * 「漏订」和「有一条没认出来」看日志时长得一模一样，而处置完全不同
     * （一个要改账本，一个要改解析）。2026-08-31 就是照着「漏订」查了半天。
     */
    @Test
    public void anAbortAlsoMentionsTheRowsItCouldNotRead() {
        VoucherLedger.Audit a = audit(
                Arrays.asList(ui(ROW_50, "20"), ui("作品相关", "20")),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪"),
                        mine(49, "49   夜色，海边")));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("漏订"));
        assertTrue(a.message, a.message.contains("读不出章号"));
        assertTrue(a.message, a.message.contains("作品相关"));
    }

    /**
     * 但<b>刚买完</b>的那一章找不到只报警：明细页页脚自己写着「清单约5分钟更新一次」。
     * 把它当失败会让「买完立刻核账」这件事必然失败 —— 那正是真买之后最该核的时刻。
     */
    @Test
    public void aFreshPurchaseNotYetInTheListOnlyWarns() {
        PurchaseRow justNow = row(ME, 51, "51   新章", 20, 0, NOW - 2 * 60_000L, WHO);
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪"), justNow));
        assertTrue(a.message, a.ok);
        assertFalse("没核对上就得说没核对", a.checked);
        assertTrue(a.message, a.message.contains("5 分钟"));
    }

    /** 明细里出现火券＝「只用代券」这条硬约束已经被破坏，哪怕章数都对得上也得停。 */
    @Test
    public void fireCoinsInTheDetailStopTheRun() {
        SubscribedDetail.Entry fire = SubscribedDetail.parseRow(ROW_50, "5", "火券", "2026-08-25");
        assertTrue(fire.fireSpent());
        VoucherLedger.Audit a = audit(Arrays.asList(fire),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪")));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("火券"));
    }

    /** 账本自己记着这一章花了火券 —— 有一边记错了。 */
    @Test
    public void fireCoinsInTheLedgerStopTheRun() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(row(ME, 50, "50   订婚事宜，梦玲失踪", 0, 20,
                        NOW - 86_400_000L, WHO)));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("火券"));
    }

    /** 章号对上了、名字对不上＝章号整体错位（作者插章／删章），接着买会买错章。 */
    @Test
    public void aTitleDriftStopsTheRun() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(mine(50, "50   完全是另一章")));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("错位"));
    }

    /** 同一章的花费两边不一样 —— 记账的那一步有问题，停下核对。 */
    @Test
    public void anAmountMismatchStopsTheRun() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "40")),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪")));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("40"));
    }

    /** 一章都没买过的号：明细空、账本空 —— 这是对上了，不是读失败。 */
    @Test
    public void anAccountThatNeverBoughtAnythingIsClean() {
        VoucherLedger.Audit a = audit(new ArrayList<SubscribedDetail.Entry>(),
                new ArrayList<PurchaseRow>());
        assertTrue(a.message, a.ok);
        assertTrue("0 对 0 也算核对过了", a.checked);
    }

    /**
     * 明细页没打开（{@code read} 返回 null）一律<b>不</b>当成对不上：读不到并不说明账本错了，
     * 把它当失败会让一句读不出来的文案永久卡死整个订阅功能。只报警。
     */
    @Test
    public void anUnreadableDetailPageOnlyWarns() {
        VoucherLedger.Audit a = audit(null, Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪")));
        assertTrue(a.message, a.ok);
        assertFalse(a.checked);
        assertTrue(a.message, a.message.contains("没能逐章核对"));

        VoucherLedger.Audit empty = audit(null, new ArrayList<PurchaseRow>());
        assertTrue(empty.message, empty.ok);
        assertFalse(empty.checked);
    }

    /** 读不出章号的条目不参与比对，但必须报出来 —— 那说明文案改了字样。 */
    @Test
    public void unparsableRowsOnlyWarn() {
        VoucherLedger.Audit a = audit(
                Arrays.asList(ui(ROW_50, "20"), ui("作品相关", "20")),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪")));
        assertTrue(a.message, a.ok);
        assertFalse(a.checked);
        assertTrue(a.message, a.message.contains("作品相关"));
    }

    /** 免费章不算「花过券」，不参与逐章比对（口径和 countPaidPurchases 一致）。 */
    @Test
    public void freeChaptersAreNotCounted() {
        PurchaseRow free = row(ME, 3, "3   免费章", 0, 0, NOW - 86_400_000L, WHO);
        free.source = "OWNED";
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪"), free));
        assertTrue(a.message, a.ok);
        assertTrue(a.message, a.checked);
    }

    // ---------- 造树：明细页 ----------

    /** 明细里的一条，按 2026-08-25 真机 dump：tvTime 日期、tvDesc 正文、右侧无 id 的金额与币种。 */
    private static FakeNode detailRow(String date, String desc, String amount, int top) {
        return FakeNode.node().withClass("android.widget.LinearLayout").clickable(true)
                .withBounds(0, top, 1080, top + 392)
                .add(FakeNode.text(date).withId("com.sfacg:id/tvTime")
                                .withBounds(425, top + 28, 655, top + 74),
                        FakeNode.text(desc).withId("com.sfacg:id/tvDesc")
                                .withBounds(110, top + 179, 637, top + 300),
                        FakeNode.text(amount).withBounds(803, top + 166, 917, top + 259),
                        FakeNode.text("代券").withBounds(803, top + 259, 917, top + 313));
    }

    private static FakeNode detailPage() {
        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("").withId("com.sfacg:id/back_img").clickable(true)
                        .withBounds(0, 111, 121, 232),
                FakeNode.text("订阅明细").withId("com.sfacg:id/title_tv")
                        .withBounds(438, 137, 642, 206),
                FakeNode.node().withClass("androidx.recyclerview.widget.RecyclerView")
                        .withId("com.sfacg:id/baseListView").scrollable(true)
                        .withBounds(0, 232, 1080, 2314)
                        .add(detailRow("2026-08-25", ROW_50, "20", 232),
                                detailRow("2026-08-24", ROW_48, "40", 624)),
                FakeNode.text("清单约5分钟更新一次，可下拉刷新").withBounds(57, 2328, 1023, 2385));
    }

    /** 清单页 —— 用来钉住「别拿 baseListView 认明细页」：两页的列表 id 是同一个。 */
    private static FakeNode listPage() {
        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("订阅清单").withId("com.sfacg:id/title_tv")
                        .withBounds(438, 137, 642, 206),
                FakeNode.node().withClass("androidx.recyclerview.widget.RecyclerView")
                        .withId("com.sfacg:id/baseListView").scrollable(true)
                        .withBounds(0, 354, 1080, 2400));
    }

    private static NodeView findOne(Map<String, List<Selector>> sel, String key, NodeView root) {
        List<Selector> candidates = sel.get(key);
        assertNotNull("selectors.json 里缺 " + key, candidates);
        NodeMatcher.Hit hit = NodeMatcher.find(root, candidates);
        return hit == null ? null : hit.node;
    }

    /**
     * 金额和币种<b>必须</b>在行内查。它们都没有 resource-id，整屏上每一条都各有一个 ——
     * 全局找会把第一条的「20」读成第二条的花费，那会凭空报出一次「花费两边不一样」。
     * 这是 {@code SubscribedDetail.rowOf} 存在的全部理由。
     */
    @Test
    public void theAmountMustBeReadInsideItsOwnRow() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        FakeNode page = detailPage();

        NodeView global = findOne(sel, Keys.SUBSCRIBED_DETAIL_AMOUNT, page);
        assertNotNull(global);
        assertEquals("全局找命中的是第一条", "20", global.text());

        List<Selector> descSelectors = sel.get(Keys.SUBSCRIBED_DETAIL_DESC);
        assertNotNull(descSelectors);
        List<NodeView> descs = new ArrayList<>();
        for (Selector s : descSelectors) descs.addAll(NodeMatcher.findAll(page, s));
        assertEquals("这一屏两条", 2, descs.size());

        NodeView secondRow = descs.get(1).parent();
        assertEquals("40", findOne(sel, Keys.SUBSCRIBED_DETAIL_AMOUNT, secondRow).text());
        assertEquals("代券", findOne(sel, Keys.SUBSCRIBED_DETAIL_CURRENCY, secondRow).text());
        assertEquals("2026-08-24", findOne(sel, Keys.SUBSCRIBED_DETAIL_TIME, secondRow).text());
        assertEquals(48, SubscribedDetail.parseRow(descs.get(1).text(), "40", "代券", null).chapterNo);
    }

    /**
     * 「到明细页了吗」只能靠 {@code title_tv}＝「订阅明细」。
     *
     * <p>清单页那个 RecyclerView 的 id 也叫 {@code baseListView}（2026-08-25 实测），
     * 拿它当判据会把「没点开」误判成「点开了」，然后在清单页上把别的书的行当成明细条目读。
     */
    @Test
    public void theReadyMarkerMustNotMatchTheListPage() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        assertNotNull(findOne(sel, Keys.SUBSCRIBED_DETAIL_READY, detailPage()));
        assertNull("清单页绝不能被当成明细页",
                findOne(sel, Keys.SUBSCRIBED_DETAIL_READY, listPage()));
    }

    /** 明细页那几条选择器一条都不能少，否则逐章对账会被静默跳过。 */
    @Test
    public void theWholeDetailPathIsConfigured() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        for (String key : new String[]{Keys.SUBSCRIBED_DETAIL_READY, Keys.SUBSCRIBED_DETAIL_DESC,
                Keys.SUBSCRIBED_DETAIL_TIME, Keys.SUBSCRIBED_DETAIL_AMOUNT,
                Keys.SUBSCRIBED_DETAIL_CURRENCY}) {
            List<Selector> candidates = sel.get(key);
            assertNotNull("selectors.json 里缺 " + key, candidates);
            assertFalse("selectors.json 里 " + key + " 是空的", candidates.isEmpty());
        }
    }
}
