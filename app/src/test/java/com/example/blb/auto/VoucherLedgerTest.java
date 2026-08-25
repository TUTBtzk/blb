package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「我的 → 代券 → 订阅清单」那一行的解析与对账，按 2026-08-25 真机 dump 造树钉住。
 *
 * <p>这一屏是账本的<b>第二来源</b>：「这一章归谁」原先只有我们自己写的购买记录一个来源，
 * 写歪了 App 自己看不出来（2026-08-25 第49章被误挂两个号，发现它的是用户，靠的正是这份清单）。
 * 所以下面每一条钉的都是「什么时候该停下不买」：
 * <ul>
 *   <li>界面比账本多＝有一章券扣了没记账（2026-08-24 那次 40 代券），接着买会有第二个号
 *       再买同一章；</li>
 *   <li>账本比界面多＝有一章被误挂给了这个号，它其实没人买，会被永远跳过（漏订）；</li>
 *   <li>清单说花了火券＝「只用代券」这条硬约束已经被破坏；</li>
 *   <li>读不到<b>不算</b>对不上 —— 否则一句读不出来的文案就能把整个订阅功能永久卡死。</li>
 * </ul>
 */
public class VoucherLedgerTest {

    private static final String BOOK = "发小竟然是后悔文男主";

    private static Map<String, List<Selector>> bundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json", f.isFile());
        return SelectorSet.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    // ---------- 解析「2章节 - 0火券」 ----------

    /** 2026-08-25 实测原文：五杯半雪碧在《发小竟然是后悔文男主》上是「2章节 - 0火券」。 */
    @Test
    public void parsesTheRealSummaryLine() {
        VoucherLedger.Reading r = VoucherLedger.parseSummary("2章节 - 0火券", "2026-08-25");
        assertTrue(r.found);
        assertTrue(r.describe(), r.known());
        assertEquals(2, r.chapters);
        assertEquals(0, r.fire);
        assertFalse("一分火券都没花", r.fireSpent());
        assertEquals("2026-08-25", r.date);
    }

    /** 花了火券就是硬约束被破坏 —— 用户不充值火券。 */
    @Test
    public void fireCoinsInTheSummaryAreAnAlarm() {
        VoucherLedger.Reading r = VoucherLedger.parseSummary("13章节 - 260火券", null);
        assertEquals(13, r.chapters);
        assertEquals(260, r.fire);
        assertTrue(r.fireSpent());
    }

    /**
     * 没写火券时 {@code fire} 是 -1（不知道），<b>不是</b> 0。
     *
     * <p>这一条和「实付」正相反：那句话没写火券就是不花火券（{@code Texts.parsePayment}），
     * 而清单这一行少写一种券只说明它没写 —— 拿「没写＝0」去套等于凭空断言「一分火券都没花」，
     * 而那正是要核对的那件事。
     */
    @Test
    public void aMissingCurrencyIsUnknownNotZero() {
        VoucherLedger.Reading r = VoucherLedger.parseSummary("2章节", null);
        assertEquals(2, r.chapters);
        assertEquals(-1, r.fire);
        assertFalse("不知道≠花了火券", r.fireSpent());
    }

    @Test
    public void unparsableSummariesStayUnknown() {
        assertFalse(VoucherLedger.parseSummary(null, null).known());
        assertFalse(VoucherLedger.parseSummary("", null).known());
        assertFalse("没有「章节」二字就不是这一行",
                VoucherLedger.parseSummary("某某作者 著", null).known());
        assertFalse(VoucherLedger.Reading.missingRow().found);
        assertFalse(VoucherLedger.Reading.missingRow().known());
    }

    // ---------- 对账 ----------

    private static VoucherLedger.Audit audit(VoucherLedger.Reading ui, int paid, int fire) {
        return VoucherLedger.reconcile("五杯半雪碧", BOOK, ui, paid, fire);
    }

    /** 2026-08-25 真机上的现场：界面 2 章、账本两条真买（第48、50章），对上了。 */
    @Test
    public void matchingCountsAreClean() {
        VoucherLedger.Audit a = audit(VoucherLedger.parseSummary("2章节 - 0火券", "2026-08-25"), 2, 0);
        assertTrue(a.message, a.ok);
        assertTrue("这是一次真的核对", a.checked);
    }

    /**
     * 界面比账本多＝账本漏记（2026-08-24 那次 40 代券扣了、一条账都没记）。
     * 不停下的话，下一趟会有第二个号再买同一章。
     */
    @Test
    public void aLedgerThatMissedAPurchaseStopsTheRun() {
        VoucherLedger.Audit a = audit(VoucherLedger.parseSummary("3章节 - 0火券", null), 2, 0);
        assertFalse(a.message, a.ok);
        assertTrue(a.checked);
        assertTrue(a.message, a.message.contains("漏记"));
    }

    /**
     * 账本比界面多＝有一章被误挂给了这个号（2026-08-25 第49章那件事的形状）。
     * 那一章其实没人买，却会被 {@code findNextUnownedChapter} 永远跳过 —— 那就是漏订。
     */
    @Test
    public void aLedgerWithAnExtraOwnerStopsTheRun() {
        VoucherLedger.Audit a = audit(VoucherLedger.parseSummary("2章节 - 0火券", null), 3, 0);
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("多记"));
    }

    /** 清单说花了火券 —— 用户不充值火券，这条硬约束被破坏就得停下，哪怕章数正好对得上。 */
    @Test
    public void fireCoinsStopTheRunEvenWhenCountsMatch() {
        VoucherLedger.Audit a = audit(VoucherLedger.parseSummary("2章节 - 40火券", null), 2, 0);
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("火券"));
    }

    /** 界面说没花火券、账本自己却记着花了 —— 有一边记错了。 */
    @Test
    public void aLedgerClaimingFireCoinsIsAlsoAnAlarm() {
        VoucherLedger.Audit a = audit(VoucherLedger.parseSummary("2章节 - 0火券", null), 2, 20);
        assertFalse(a.message, a.ok);
    }

    /** 一章都没订过的号在清单里没有这一行，账本也是 0 条 —— 这是对上了，不是读失败。 */
    @Test
    public void anAccountThatNeverBoughtAnythingMatchesAnAbsentRow() {
        VoucherLedger.Audit a = audit(VoucherLedger.Reading.missingRow(), 0, 0);
        assertTrue(a.message, a.ok);
        assertTrue("0 对 0 也算核对过了", a.checked);
    }

    /**
     * 读不到一律<b>不</b>当成对不上：清单没加载完、停在「漫画」那个 tab、书名字样和台账里记的
     * 不一样，看起来都是这一种。把它当失败会让一句读不出来的文案永久卡死整个订阅功能 ——
     * 所以只报警（{@code checked=false}，调用方会把这句话写进队列小结）。
     */
    @Test
    public void anUnreadableListOnlyWarns() {
        VoucherLedger.Audit missing = audit(VoucherLedger.Reading.missingRow(), 2, 0);
        assertTrue(missing.message, missing.ok);
        assertFalse("没核对上就得说没核对", missing.checked);
        assertTrue(missing.message, missing.message.contains("请核对"));

        VoucherLedger.Audit garbled = audit(VoucherLedger.parseSummary("加载中…", null), 2, 0);
        assertTrue(garbled.message, garbled.ok);
        assertFalse(garbled.checked);
    }

    // ---------- 造树：清单里的一行 ----------

    /** 清单里的一行，按 2026-08-25 真机 dump：书名／摘要／日期／「查看目录」，整行可点。 */
    private static FakeNode bookRow(String title, String summary, String date, int top) {
        return FakeNode.node().withClass("android.widget.LinearLayout").clickable(true)
                .withBounds(0, top, 1080, top + 354)
                .add(FakeNode.text(title).withId("com.sfacg:id/tvbBookTitle")
                                .withBounds(300, top + 30, 900, top + 100),
                        FakeNode.text(summary).withId("com.sfacg:id/tvbBookAutor")
                                .withBounds(300, top + 120, 900, top + 180),
                        FakeNode.text(date).withId("com.sfacg:id/tvbBookDesc")
                                .withBounds(300, top + 200, 900, top + 260),
                        FakeNode.text("查看目录").withId("com.sfacg:id/tvToMuLu").clickable(true)
                                .withBounds(700, top + 270, 900, top + 340));
    }

    private static FakeNode listPage() {
        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(
                FakeNode.text("订阅清单").withId("com.sfacg:id/title_tv")
                        .withBounds(400, 100, 680, 170),
                FakeNode.text("轻小说").withClass("android.app.ActionBar$Tab").clickable(true)
                        .withBounds(0, 200, 360, 280),
                FakeNode.node().withClass("androidx.recyclerview.widget.RecyclerView")
                        .scrollable(true).withBounds(0, 300, 1080, 2400)
                        .add(bookRow("别的书", "9章节 - 180火券", "2026-08-01", 310),
                                bookRow(BOOK, "2章节 - 0火券", "2026-08-25", 680)));
    }

    private static NodeView findOne(Map<String, List<Selector>> sel, String key, NodeView root) {
        List<Selector> candidates = sel.get(key);
        assertNotNull("selectors.json 里缺 " + key, candidates);
        NodeMatcher.Hit hit = NodeMatcher.find(root, candidates);
        return hit == null ? null : hit.node;
    }

    /**
     * 摘要<b>必须</b>在行内查。整屏上到处都是别的书的 {@code tvbBookAutor}，
     * 全局找会把上一本书的「9章节 - 180火券」读成这一本的 —— 那会凭空报出一次「花了火券」，
     * 也会把对账彻底搞反。这是 {@code VoucherLedger.rowOf} 存在的全部理由。
     */
    @Test
    public void theSummaryMustBeReadInsideItsOwnRow() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        FakeNode page = listPage();

        NodeView global = findOne(sel, Keys.SUBSCRIBED_BOOK_SUMMARY, page);
        assertNotNull(global);
        assertEquals("全局找命中的是第一本书", "9章节 - 180火券", global.text());

        // 按书名找到那一行（和 StepRunner.findRowWithText 同一套：限定 id + 运行时拼上书名）。
        List<Selector> titleSelectors = sel.get(Keys.SUBSCRIBED_BOOK_TITLE);
        assertNotNull(titleSelectors);
        List<NodeView> titles = new ArrayList<>();
        for (Selector s : titleSelectors) titles.addAll(NodeMatcher.findAll(page, s.withText(BOOK)));
        assertEquals("书名节点只该命中一个", 1, titles.size());
        NodeView title = titles.get(0);
        assertFalse("书名本身不可点，可点的是整行", title.clickable());

        NodeView row = title.parent();
        NodeView inRow = findOne(sel, Keys.SUBSCRIBED_BOOK_SUMMARY, row);
        assertNotNull(inRow);
        assertEquals("2章节 - 0火券", inRow.text());
        assertEquals(2, VoucherLedger.parseSummary(inRow.text(),
                findOne(sel, Keys.SUBSCRIBED_BOOK_DATE, row).text()).chapters);
        assertEquals("2026-08-25", VoucherLedger.parseSummary(inRow.text(),
                findOne(sel, Keys.SUBSCRIBED_BOOK_DATE, row).text()).date);
    }

    /** 清单页那三屏的选择器一条都不能少，否则对账会被静默跳过。 */
    @Test
    public void theWholePathIsConfigured() throws Exception {
        Map<String, List<Selector>> sel = bundled();
        for (String key : new String[]{Keys.MINE_TAB, Keys.VOUCHER_ENTRY,
                Keys.SUBSCRIBED_LIST_ENTRY, Keys.SUBSCRIBED_BOOK_TITLE,
                Keys.SUBSCRIBED_BOOK_SUMMARY, Keys.SUBSCRIBED_BOOK_DATE}) {
            List<Selector> candidates = sel.get(key);
            assertNotNull("selectors.json 里缺 " + key, candidates);
            assertFalse("selectors.json 里 " + key + " 是空的", candidates.isEmpty());
        }
    }
}
