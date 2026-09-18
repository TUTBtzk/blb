package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.blb.data.Chapter;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.util.Texts;

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
 * <p>2026-09-14 用户确认多号订同章是真实历史；测试锁定各号账本与各号明细相等，
 * 跨号真实记录不再当成错误。聚合只答得出总数，逐章比对才能指出究竟漏记哪一笔。
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

    /** 滚动重叠只去掉完整相同的 UI 行，同章但交易事实不同的两行必须交给恢复层拒绝。 */
    @Test
    public void detailIdentityIncludesEveryTransactionFact() {
        SubscribedDetail.Entry sameA = SubscribedDetail.parseRow(
                ROW_50, "20", "代券", "2026-08-25");
        SubscribedDetail.Entry sameB = SubscribedDetail.parseRow(
                ROW_50, "20", "代券", "2026-08-25");
        SubscribedDetail.Entry differentAmount = SubscribedDetail.parseRow(
                ROW_50, "40", "代券", "2026-08-25");
        SubscribedDetail.Entry differentDate = SubscribedDetail.parseRow(
                ROW_50, "20", "代券", "2026-08-24");

        assertEquals("完整相同的滚动重叠应去重", sameA.key(), sameB.key());
        assertFalse("同章不同金额不能被吞", sameA.key().equals(differentAmount.key()));
        assertFalse("同章不同日期不能被吞", sameA.key().equals(differentDate.key()));
    }

    @Test
    public void parsesChineseOrdinalRowsFromTheRealPage() {
        SubscribedDetail.Entry secondVolume = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        assertEquals(6, secondVolume.chapterNo);
        assertEquals("星彩", secondVolume.volume);
        assertEquals("薯片", secondVolume.title);

        SubscribedDetail.Entry firstVolume = SubscribedDetail.parseRow(
                "铃兰花 第65章薄暮", "12", "代券", "2026-09-08");
        assertEquals(65, firstVolume.chapterNo);
        assertEquals("铃兰花", firstVolume.volume);
        assertEquals("薄暮", firstVolume.title);
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

    @Test
    public void explicitNumberWinsOverAnOrdinalInsideTheVolumeName() {
        SubscribedDetail.Entry e = SubscribedDetail.parseRow(
                "2023年第6章纪念 番外 50 真正标题", "20", "代券", null);
        assertEquals(50, e.chapterNo);
        assertEquals("2023年第6章纪念 番外", e.volume);
        assertEquals("真正标题", e.title);
    }

    @Test
    public void spacedChineseOrdinalRowsStillParse() {
        SubscribedDetail.Entry compact = SubscribedDetail.parseRow(
                "铃兰花 第65 章 薄暮", "12", "代券", "2026-09-08");
        SubscribedDetail.Entry spaced = SubscribedDetail.parseRow(
                "铃兰花 第 65 章 薄暮", "12", "代券", "2026-09-08");
        assertEquals(65, compact.chapterNo);
        assertEquals("薄暮", compact.title);
        assertEquals(65, spaced.chapterNo);
        assertEquals("薄暮", spaced.title);
    }

    @Test
    public void bodyNumbersCannotOverrideAnEarlierOrdinalChapterMarker() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "铃兰花 第83章 最后 2026", "11", "代券", "2026-09-12");
        assertEquals(83, entry.chapterNo);
        assertEquals("铃兰花", entry.volume);
        assertEquals("最后 2026", entry.title);
    }

    @Test
    public void aChapterReferenceInsideTheBodyCannotOverrideTheRealHeading() {
        for (String desc : new String[]{"铃兰花 第83章 回顾 第2章", "铃兰花 83 回顾 第2章"}) {
            SubscribedDetail.Entry entry = SubscribedDetail.parseRow(desc, "11", "代券", "2026-09-12");
            assertEquals(desc, 83, entry.chapterNo);
            assertEquals(desc, "回顾 第2章", entry.title);
        }
    }

    @Test
    public void missingAndMistypedMarkersFromTheRealCatalogStillParseAndMatch() {
        SubscribedDetail.Entry missing = SubscribedDetail.parseRow(
                "铃兰花 第68 投影", "12", "代券", "2026-09-12");
        assertEquals(68, missing.chapterNo);
        assertEquals("铃兰花", missing.volume);
        assertEquals("投影", missing.title);
        assertTrue(SubscribedDetail.sameChapter("第68 投影", missing.title));
        assertTrue(SubscribedDetail.sameChapter("第68章 投影", missing.title));

        SubscribedDetail.Entry typo = SubscribedDetail.parseRow(
                "红莲 第30张 红温", "12", "代券", "2026-09-12");
        assertEquals(30, typo.chapterNo);
        assertEquals("红莲", typo.volume);
        assertEquals("红温", typo.title);
        assertTrue(SubscribedDetail.sameChapter("第30张 红温", typo.title));
        assertTrue(SubscribedDetail.sameChapter("30 红温", typo.title));
    }

    @Test
    public void volumeMarkersCannotFallBackToAnOrdinaryNumericChapter() {
        for (String desc : new String[]{"第68卷", "第68 卷", "第 68 卷", "第 68卷",
                "第68部", "第68 部", "第68册", "第68 册"}) {
            assertFalse(desc, SubscribedDetail.parseRow(desc, "12", "代券", "2026-09-12").known());
        }
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
        p.chapterId = chapterNo * 10L;
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
        assertTrue(SubscribedDetail.sameChapter("第50章 订婚事宜，梦玲失踪", "订婚事宜，梦玲失踪"));
        assertTrue("没登记标题谈不上对不上", SubscribedDetail.sameChapter(null, "订婚事宜"));
        assertFalse(SubscribedDetail.sameChapter("50   周日工作", "订婚事宜，梦玲失踪"));
    }

    @Test
    public void anAlreadyParsedTitleKeepsItsLeadingNumbersAndChapterReferences() {
        assertTrue(SubscribedDetail.sameChapter("第83章100天后", "100天后"));
        assertTrue(SubscribedDetail.sameChapter("83 100天后", "100天后"));
        assertTrue(SubscribedDetail.sameChapter("第83章 第2章的秘密", "第2章的秘密"));
        assertTrue(SubscribedDetail.sameChapter("第68 第2章的秘密", "第2章的秘密"));
        assertTrue(SubscribedDetail.sameChapter("100天后", "100天后"));
        assertFalse(SubscribedDetail.sameChapter("第83章100天后", "200天后"));
        assertFalse(SubscribedDetail.sameChapter("100天后", "200天后"));
    }

    @Test
    public void aPartialTitleCannotStandInForADifferentCompleteTitle() {
        assertFalse(SubscribedDetail.sameChapter("第83章 最后", "最后的约定"));
        assertFalse(SubscribedDetail.sameChapter("第83章 最后的约定", "最后"));
        assertFalse(SubscribedDetail.sameChapter("83 最后", "最后的约定"));
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
     * 2026-09-14 的真实多号订阅：其他号已有记录不能替代当前号的记录；先明确本号漏记再补。
     */
    @Test
    public void anotherAccountsRecordDoesNotHideTheCurrentAccountsMissingFact() {
        VoucherLedger.Audit a = audit(Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(row(OTHER, 50, "50   订婚事宜，梦玲失踪", 20, 0,
                        NOW - 86_400_000L, "皓平")));
        assertFalse(a.message, a.ok);
        assertTrue(a.message, a.message.contains("皓平"));
        assertTrue(a.message, a.message.contains("第50章"));
        assertTrue(a.message, a.message.contains("本号账本漏记"));
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

    @Test
    public void anExplicitFireCurrencyStopsEvenWhenTheAmountIsUnreadable() {
        SubscribedDetail.Entry fire = SubscribedDetail.parseRow(
                ROW_50, null, "火券", "2026-08-25");
        assertTrue(fire.fireSpent());
        VoucherLedger.Audit audit = audit(Arrays.asList(fire),
                Arrays.asList(mine(50, "50   订婚事宜，梦玲失踪")));
        assertFalse(audit.message, audit.ok);
        assertTrue(audit.message, audit.message.contains("火券"));
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

    private static Chapter chapter(long id, int no, String title) {
        Chapter c = new Chapter();
        c.id = id;
        c.novelId = 1;
        c.chapterNo = no;
        c.title = title;
        return c;
    }

    private static Chapter chapter(long id, int no, String title, String volume) {
        Chapter c = chapter(id, no, title);
        c.volumeTitle = volume;
        return c;
    }

    // ---------- 2026-09-16 真机：作者把章名留空（目录里那一行就写着「星彩 第24章」） ----------

    /**
     * 现场：`blb-log-失败..txt` L148 那条明细原文就是「星彩 第24章 10 代券」——
     * 标题是空的，卷名和印刷章号都在。以前这种被算成「缺少 章节身份」→ 整个账号
     * 「本账号保持未核实」→ MONEY_UNCLEAR → 整趟停在这里。
     */
    @Test
    public void aChapterWhoseTitleTheAuthorLeftBlankIsStillACompleteIdentity() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第24章", "10", "代券", "2026-09-15");
        assertEquals("卷名读出来了", "星彩", entry.volume);
        assertEquals("印刷章号读出来了", 24, entry.chapterNo);
        assertTrue("空标题不是没读到 —— 卷名+章号已经够定位", entry.hasChapterIdentity());
        assertTrue("金额、币种、日期齐全时这一条算完整", SubscribedDetail.completeTransaction(entry));
        assertTrue("日志要能看出是作者没写章名", entry.describe().contains("作者没写章名"));

        // 对账本：目录扫描把这一行存成「第24章」，卷名是「星彩」。
        Chapter ledger = chapter(2400, 100, "第24章", "星彩");
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(entry), Arrays.asList(ledger));
        assertTrue(resolved.message, resolved.ok);
        assertEquals(1, resolved.resolved.size());
        assertEquals(2400, resolved.resolved.get(0).chapter.id);
    }

    /** 同一个印刷章号在不同卷里会重复：缺卷名时不许猜，仍然算缺身份。 */
    @Test
    public void aBlankTitleWithoutTheVolumeIsStillMissingItsIdentity() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "第24章", "10", "代券", "2026-09-15");
        assertTrue(Texts.isBlank(entry.volume));
        assertFalse("卷名读不到就不许认", entry.hasChapterIdentity());
        assertFalse(SubscribedDetail.completeTransaction(entry));
        assertFalse(RemoteLedgerRecovery.resolveAll("", Arrays.asList(entry),
                Arrays.asList(chapter(2400, 100, "第24章", "星彩"))).ok);
    }

    /** 卷名对不上时不能落到另一卷的同一号上。 */
    @Test
    public void aBlankTitleIsMatchedByVolumeNotOnlyByNumber() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第24章", "10", "代券", "2026-09-15");
        Chapter right = chapter(2400, 100, "第24章", "星彩");
        Chapter wrongVolume = chapter(2500, 124, "第24章", "铃兰花");
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(entry), Arrays.asList(right, wrongVolume));
        assertTrue(resolved.message, resolved.ok);
        assertEquals("只能落到同卷那一行", 2400, resolved.resolved.get(0).chapter.id);
    }

    /** 护栏不放宽：空标题也一样要求金额、币种、日期齐全。 */
    @Test
    public void aBlankTitleStillNeedsItsMoneyCurrencyAndDate() {
        String[] amounts = {null, "10", "10"};
        String[] currencies = {"代券", null, "代券"};
        String[] dates = {"2026-09-15", "2026-09-15", null};
        for (int i = 0; i < amounts.length; i++) {
            SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                    "星彩 第24章", amounts[i], currencies[i], dates[i]);
            assertTrue(entry.hasChapterIdentity());
            assertFalse("缺金额/币种/日期仍然不算完整（第 " + i + " 组）",
                    SubscribedDetail.completeTransaction(entry));
        }
        assertTrue("三项齐全才算完整",
                SubscribedDetail.completeTransaction(SubscribedDetail.parseRow(
                        "星彩 第24章", "10", "代券", "2026-09-15")));
    }

    /** 聚合 2 对 0 时，完整逐章明细能精确生成两条历史补账，不会猜「前两章」。 */
    @Test
    public void completeRemoteDetailsCanPlanARecovery() {
        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 2,
                Arrays.asList(ui(ROW_50, "20"), ui(ROW_48, "40")),
                Arrays.asList(chapter(500, 50, "50   订婚事宜，梦玲失踪"),
                        chapter(480, 48, "48   滤镜破碎，总裁秘书")),
                new ArrayList<PurchaseRow>());

        assertTrue(p.message, p.ok);
        assertEquals(2, p.purchases.size());
        assertEquals(500, p.purchases.get(0).chapterId);
        assertEquals(20, p.purchases.get(0).costVouchers);
        assertEquals(Purchase.SRC_REMOTE_DETAIL, p.purchases.get(0).source);
        assertTrue("购买日期来自远端，不能算成补账当天",
                p.purchases.get(0).purchasedAt < System.currentTimeMillis() - 86_400_000L);
    }

    /** 聚合数、逐章数、目录标题有任何一处不能互证，就保持停机，不写半本账。 */
    @Test
    public void incompleteOrDriftedRemoteDetailsCannotPlanARecovery() {
        RemoteLedgerRecovery.Plan incomplete = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 2,
                Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(chapter(500, 50, "50   订婚事宜，梦玲失踪")),
                new ArrayList<PurchaseRow>());
        assertFalse(incomplete.message, incomplete.ok);
        assertTrue(incomplete.purchases.isEmpty());

        RemoteLedgerRecovery.Plan drifted = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(chapter(500, 50, "50   完全是另一章")),
                new ArrayList<PurchaseRow>());
        assertFalse(drifted.message, drifted.ok);
        assertTrue(drifted.message, drifted.message.contains("标题对不上"));
    }

    /** 同章出现两条不同的远端事实必须拒绝，不能挑第一条补账后掩盖异常扣费。 */
    @Test
    public void conflictingDuplicateRemoteDetailsCannotPlanARecovery() {
        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(ui(ROW_50, "20"), ui(ROW_50, "40")),
                Arrays.asList(chapter(500, 50, "50   订婚事宜，梦玲失踪")),
                new ArrayList<PurchaseRow>());
        assertFalse(p.message, p.ok);
        assertTrue(p.message, p.message.contains("重复出现"));
        assertTrue(p.purchases.isEmpty());
    }

    /** 分卷后卷内标号仍可凭标题唯一对应到正确的全书行。 */
    @Test
    public void aResetVolumeNumberResolvesByPrintedNumberAndTitle() {
        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(SubscribedDetail.parseRow(
                        "番外卷 第1章 番外一", "20", "代券", "2026-08-25")),
                Arrays.asList(chapter(613, 613, "1 番外一")),
                new ArrayList<PurchaseRow>());

        assertTrue(p.message, p.ok);
        assertEquals(613, p.purchases.get(0).chapterId);
        assertEquals(613, p.resolved.get(0).chapter.chapterNo);
    }

    @Test
    public void realMultiVolumeRowsResolveToGlobalChapterIds() {
        SubscribedDetail.Entry beach = SubscribedDetail.parseRow(
                "铃兰花 第81章 沙滩", "12", "代券", "2026-04-03");
        SubscribedDetail.Entry chips = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 2,
                Arrays.asList(beach, chips),
                Arrays.asList(chapter(810, 81, "81 心脏"),
                        chapter(820, 82, "81 沙滩"),
                        chapter(60, 6, "6 和流浪汉见个面"),
                        chapter(990, 99, "6 薯片")),
                new ArrayList<PurchaseRow>());

        assertTrue(p.message, p.ok);
        assertEquals(82, p.resolved.get(0).chapter.chapterNo);
        assertEquals(99, p.resolved.get(1).chapter.chapterNo);
        assertEquals(820, p.purchases.get(0).chapterId);
        assertEquals(990, p.purchases.get(1).chapterId);
    }

    @Test
    public void resolvedAuditUsesGlobalChapterId() {
        SubscribedDetail.Entry chips = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        Chapter global99 = chapter(990, 99, "6 薯片");
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(chips), Arrays.asList(global99));
        PurchaseRow paid = row(ME, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), WHO);
        paid.chapterId = 990;

        VoucherLedger.Audit audit = RemoteLedgerRecovery.auditResolved(WHO, BOOK, ME,
                resolved.resolved, Arrays.asList(paid), NOW);
        assertTrue(audit.message, audit.ok);
        assertTrue(audit.message, audit.checked);
        assertTrue(audit.message, audit.message.contains("第99章"));
    }

    @Test
    public void allSevenRealTransactionsResolveToTheirGlobalChapters() {
        List<SubscribedDetail.Entry> entries = Arrays.asList(
                SubscribedDetail.parseRow("铃兰花 第83章 最后", "11", "代券", "2026-09-12"),
                SubscribedDetail.parseRow("星彩 第6章 薯片", "10", "代券", "2026-09-08"),
                SubscribedDetail.parseRow("铃兰花 第65章 薄暮", "12", "代券", "2026-09-08"),
                SubscribedDetail.parseRow("铃兰花 第92章 初次见面", "12", "代券", "2026-09-08"),
                SubscribedDetail.parseRow("铃兰花 第91章 花叶", "11", "代券", "2026-04-05"),
                SubscribedDetail.parseRow("铃兰花 第81章 沙滩", "12", "代券", "2026-04-03"),
                SubscribedDetail.parseRow("铃兰花 第71章 前夜", "10", "代券", "2026-03-30"));
        List<Chapter> catalog = Arrays.asList(
                chapter(650, 65, "65 薄暮"), chapter(710, 71, "71 前夜"),
                chapter(810, 81, "81 心脏"), chapter(820, 82, "81 沙滩"),
                chapter(830, 83, "83 最后"),
                chapter(910, 91, "91 花叶"), chapter(920, 92, "92 初次见面"),
                chapter(60, 6, "6 和流浪汉见个面"), chapter(990, 99, "6 薯片"));

        for (boolean ordinal : new boolean[]{false, true}) {
            if (ordinal) {
                for (Chapter chapter : catalog) {
                    int separator = chapter.title.indexOf(' ');
                    chapter.title = "第" + chapter.title.substring(0, separator)
                            + "章" + chapter.title.substring(separator);
                }
            }
            RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(
                    WHO, BOOK, ME, 7, entries, catalog, new ArrayList<PurchaseRow>());

            assertTrue(plan.message, plan.ok);
            assertEquals(7, plan.purchases.size());
            List<Integer> global = new ArrayList<>();
            int vouchers = 0;
            for (RemoteLedgerRecovery.Resolved item : plan.resolved) {
                global.add(item.chapter.chapterNo);
            }
            for (Purchase purchase : plan.purchases) vouchers += purchase.costVouchers;
            java.util.Collections.sort(global);
            assertEquals(Arrays.asList(65, 71, 82, 83, 91, 92, 99), global);
            assertEquals(78, vouchers);
        }
    }

    @Test
    public void recoveryPreservesNumbersInsideAnOrdinalChapterTitle() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "铃兰花 第83章 100天后的最后 2026", "11", "代券", "2026-09-12");
        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(entry),
                Arrays.asList(chapter(830, 83, "第83章 100天后的最后 2026")),
                new ArrayList<PurchaseRow>());

        assertTrue(plan.message, plan.ok);
        assertEquals(830, plan.purchases.get(0).chapterId);
        assertEquals(11, plan.purchases.get(0).costVouchers);
    }

    @Test
    public void resolvedAuditRejectsIncompleteMoneyAndDates() {
        Chapter global99 = chapter(990, 99, "6 薯片");
        PurchaseRow paid = row(ME, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), WHO);
        paid.chapterId = 990;

        String[] amounts = {null, "10", "10", "10"};
        String[] currencies = {"代券", null, "代券", "代券"};
        String[] dates = {"2026-09-08", "2026-09-08", null, "2026-09-07"};
        for (int i = 0; i < amounts.length; i++) {
            SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                    "星彩 第6章 薯片", amounts[i], currencies[i], dates[i]);
            RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                    Arrays.asList(entry), Arrays.asList(global99));
            VoucherLedger.Audit audit = RemoteLedgerRecovery.auditResolved(
                    WHO, BOOK, ME, resolved.resolved, Arrays.asList(paid), NOW);
            assertFalse(audit.message, audit.ok);
        }
    }

    @Test
    public void resolvedNormalAuditOnlyWarnsAboutUnreadableRowsAndFacts() {
        Chapter global99 = chapter(990, 99, "6 薯片");
        PurchaseRow paid = row(ME, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), WHO);
        paid.chapterId = 990;
        SubscribedDetail.Entry incomplete = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", null, null, null);
        SubscribedDetail.Entry unknown = SubscribedDetail.parseRow(
                "作品相关", "10", "代券", "2026-09-08");

        VoucherLedger.Audit audit = SubscribedDetail.reconcile(WHO, ME, BOOK,
                Arrays.asList(incomplete, unknown), Arrays.asList(global99),
                Arrays.asList(paid), NOW);

        assertTrue(audit.message, audit.ok);
        assertFalse(audit.message, audit.checked);
        assertTrue(audit.message, audit.message.contains("读不到"));
        assertTrue(audit.message, audit.message.contains("作品相关"));
    }

    @Test
    public void resolvedNormalAuditDoesNotRejectAValidPurchaseInAnotherTimezoneDay() {
        Chapter global99 = chapter(990, 99, "6 薯片");
        PurchaseRow paid = row(ME, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-07"), WHO);
        paid.chapterId = 990;
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");

        VoucherLedger.Audit audit = SubscribedDetail.reconcile(WHO, ME, BOOK,
                Arrays.asList(entry), Arrays.asList(global99), Arrays.asList(paid), NOW);

        assertTrue(audit.message, audit.ok);
        assertTrue(audit.message, audit.checked);
    }

    @Test
    public void resolvedRecoveryAuditDoesNotUseTheFreshSyncGracePeriod() {
        SubscribedDetail.Entry chips = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(chips), Arrays.asList(chapter(990, 99, "6 薯片")));
        PurchaseRow unexpected = row(ME, 100, "7 下一章", 10, 0,
                NOW - 2 * 60_000L, WHO);
        unexpected.chapterId = 1000;

        VoucherLedger.Audit audit = RemoteLedgerRecovery.auditResolved(
                WHO, BOOK, ME, resolved.resolved, Arrays.asList(unexpected), NOW);

        assertFalse(audit.message, audit.ok);
        assertTrue(audit.message, audit.message.contains("第100章"));
        assertFalse(audit.message, audit.message.contains("先放过"));
    }

    @Test
    public void currentAndForeignPaidOwnersAreValidWhenCurrentRemoteFactsMatch() {
        SubscribedDetail.Entry chips = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(chips), Arrays.asList(chapter(990, 99, "6 薯片")));
        PurchaseRow mine = row(ME, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), WHO);
        PurchaseRow foreign = row(OTHER, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), "皓平");
        mine.chapterId = 990;
        foreign.chapterId = 990;

        VoucherLedger.Audit audit = RemoteLedgerRecovery.auditResolved(
                WHO, BOOK, ME, resolved.resolved, Arrays.asList(mine, foreign), NOW);

        assertTrue(audit.message, audit.ok);
        assertTrue(audit.message, audit.checked);
        assertTrue(audit.message, audit.message.contains("逐章都对上了"));
    }

    @Test
    public void multipleForeignOwnersStillLeaveTheCurrentAccountToBeBackfilled() {
        SubscribedDetail.Entry chips = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(chips), Arrays.asList(chapter(990, 99, "6 薯片")));
        PurchaseRow first = row(OTHER, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), "皓平");
        PurchaseRow second = row(OTHER + 1, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), "另一个号");
        first.chapterId = 990;
        second.chapterId = 990;

        VoucherLedger.Audit audit = RemoteLedgerRecovery.auditResolved(
                WHO, BOOK, ME, resolved.resolved, Arrays.asList(first, second), NOW);
        assertFalse(audit.message, audit.ok);
        assertTrue(audit.message, audit.message.contains("本号账本漏记"));
        assertFalse(audit.message, audit.message.contains("多个其他账号"));
    }

    @Test
    public void plannerNamesMultipleForeignPaidOwners() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        PurchaseRow first = row(OTHER, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), "甲");
        first.chapterId = 990;
        PurchaseRow second = row(OTHER + 1, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), "乙");
        second.chapterId = 990;

        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(entry), Arrays.asList(chapter(990, 99, "6 薯片")),
                Arrays.asList(first, second));

        assertTrue(plan.message, plan.ok);
        assertEquals(1, plan.purchases.size());
        assertEquals(Purchase.SRC_REMOTE_DETAIL, plan.purchases.get(0).source);
        String note = plan.backfillNotes.get(990L);
        assertTrue(note, note.contains("甲"));
        assertTrue(note, note.contains("乙"));
        assertTrue(note, note.contains("已照实补记"));

        PurchaseRow restored = row(ME, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), WHO);
        restored.source = Purchase.SRC_REMOTE_DETAIL;
        VoucherLedger.Audit checked = RemoteLedgerRecovery.auditResolved(WHO, BOOK, ME,
                plan.resolved, Arrays.asList(first, second, restored), NOW);
        assertTrue(checked.message, checked.ok);
        assertTrue(checked.message, checked.checked);
    }

    @Test
    public void ambiguousCatalogMappingOnlyWarnsDuringRoutineAudit() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        PurchaseRow paid = row(ME, 99, "6 薯片", 10, 0,
                RemoteLedgerRecovery.date("2026-09-08"), WHO);
        paid.chapterId = 990;

        VoucherLedger.Audit audit = SubscribedDetail.reconcile(WHO, ME, BOOK,
                Arrays.asList(entry),
                Arrays.asList(chapter(60, 6, "6 薯片"), chapter(990, 99, "第6章 薯片")),
                Arrays.asList(paid), NOW);

        assertTrue(audit.message, audit.ok);
        assertFalse(audit.message, audit.checked);
        assertTrue(audit.message, audit.message.contains("没能逐章核对"));
    }

    @Test
    public void aLongerDistinctTitleDoesNotMakeAnExactCandidateAmbiguous() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");

        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(entry),
                Arrays.asList(chapter(60, 6, "6 薯片"),
                        chapter(990, 99, "6 薯片与可乐")),
                new ArrayList<PurchaseRow>());

        assertTrue(plan.message, plan.ok);
        assertEquals(1, plan.purchases.size());
        assertEquals(60, plan.purchases.get(0).chapterId);
    }

    @Test
    public void aLongerDistinctTitleAloneCannotBeRecoveredAsTheShorterTitle() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(entry), Arrays.asList(chapter(990, 99, "第6章 薯片与可乐")),
                new ArrayList<PurchaseRow>());

        assertFalse(plan.message, plan.ok);
        assertTrue(plan.purchases.isEmpty());
    }

    @Test
    public void numberAndTitlePointingToDifferentRowsIsDiagnosed() {
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                "星彩 第6章 薯片", "10", "代券", "2026-09-08");
        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(entry), Arrays.asList(chapter(70, 7, "7 薯片")),
                new ArrayList<PurchaseRow>());

        assertFalse(plan.message, plan.ok);
        assertTrue(plan.message, plan.message.contains("分别指向不同目录行"));
        assertTrue(plan.purchases.isEmpty());
    }

    @Test
    public void ambiguousPrintedNumberAndTitleCannotRecover() {
        SubscribedDetail.Entry duplicate = SubscribedDetail.parseRow(
                "未知卷 第6章 薯片", "10", "代券", "2026-09-08");
        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(duplicate),
                Arrays.asList(chapter(60, 6, "6 薯片"), chapter(990, 99, "6 薯片")),
                new ArrayList<PurchaseRow>());

        assertFalse(p.message, p.ok);
        assertTrue(p.message, p.message.contains("标题对不上") || p.message.contains("不能唯一"));
        assertTrue(p.purchases.isEmpty());
    }

    /** 严格日期格式还不够：未来日期不是已经发生的购买，不能写账或占用未来每日额度。 */
    @Test
    public void aFutureRemoteDateCannotPlanARecovery() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.ROOT);
        java.util.Calendar future = java.util.Calendar.getInstance();
        future.add(java.util.Calendar.DAY_OF_YEAR, 2);
        SubscribedDetail.Entry entry = SubscribedDetail.parseRow(
                ROW_50, "20", "代券", f.format(future.getTime()));

        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(entry),
                Arrays.asList(chapter(500, 50, "50   订婚事宜，梦玲失踪")),
                new ArrayList<PurchaseRow>());

        assertFalse(p.message, p.ok);
        assertTrue(p.message, p.message.contains("在今天之后"));
        assertTrue(p.purchases.isEmpty());
    }

    /** 已有账的金额和标题即使相同，日期对不上也不能把它当成同一笔历史购买。 */
    @Test
    public void anExistingPurchaseWithAnotherDateBlocksRecovery() {
        PurchaseRow existing = row(ME, 50, "50   订婚事宜，梦玲失踪",
                20, 0, NOW - 86_400_000L, WHO);
        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 2,
                Arrays.asList(ui(ROW_50, "20"), ui(ROW_48, "20")),
                Arrays.asList(chapter(500, 50, "50   订婚事宜，梦玲失踪"),
                        chapter(480, 48, "48   滤镜破碎，总裁秘书")),
                Arrays.asList(existing));

        assertFalse(p.message, p.ok);
        assertTrue(p.message, p.message.contains("金额或日期"));
        assertTrue(p.purchases.isEmpty());
    }

    /** 2026-09-14 用户确认两个号都真买过；完整明细必须补上本号，不能把历史事实拦成存疑。 */
    @Test
    public void anotherAccountsOwnershipAllowsRemoteRecoveryWithANeutralNote() {
        RemoteLedgerRecovery.Plan p = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(chapter(500, 50, "50   订婚事宜，梦玲失踪")),
                Arrays.asList(row(OTHER, 50, "50   订婚事宜，梦玲失踪", 20, 0,
                        NOW - 86_400_000L, "皓平")));
        assertTrue(p.message, p.ok);
        assertEquals(1, p.purchases.size());
        assertEquals(ME, p.purchases.get(0).accountId);
        assertEquals(500, p.purchases.get(0).chapterId);
        String note = p.backfillNotes.get(500L);
        assertTrue(note, note.contains("皓平"));
        assertTrue(note, note.contains("已照实补记"));
        assertFalse(note, note.contains("存疑"));
    }

    @Test
    public void aDuplicateFactForTheSameAccountStillCannotPlanRecovery() {
        PurchaseRow duplicated = row(OTHER, 50, "50   订婚事宜，梦玲失踪", 20, 0,
                RemoteLedgerRecovery.date("2026-08-25"), "皓平");
        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                Arrays.asList(ui(ROW_50, "20")),
                Arrays.asList(chapter(500, 50, "50   订婚事宜，梦玲失踪")),
                Arrays.asList(duplicated, duplicated));
        assertFalse(plan.message, plan.ok);
        assertTrue(plan.message, plan.message.contains("同一账号有重复"));
        assertTrue(plan.purchases.isEmpty());
    }

    private static final String EXTRA_TITLE = "藏在地下室的恶鬼（上）";

    private static Chapter extra(long id, int position, String volume) {
        Chapter chapter = chapter(id, position, EXTRA_TITLE);
        chapter.volumeTitle = volume;
        return chapter;
    }

    /** 标题取自2026-09-14用户节点；卷名前缀是明细格式的纯判据输入，不冒充已购番外明细 dump。 */
    @Test
    public void unnumberedExtraUsesTheExplicitVolumeAndFullTitleWithoutInventingANumber() {
        Chapter extra = extra(6120, 612, "【番外】");
        for (String volume : new String[]{"番外", "【番外】"}) {
            SubscribedDetail.Entry raw = SubscribedDetail.parseRow(volume + " " + EXTRA_TITLE,
                    "12", "代券", "2026-09-08");
            assertFalse(raw.known());
            RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                    Arrays.asList(raw), Arrays.asList(extra), new ArrayList<PurchaseRow>());
            assertTrue(plan.message, plan.ok);
            assertEquals(6120L, plan.purchases.get(0).chapterId);
            assertEquals(612, plan.resolved.get(0).chapter.chapterNo);
            assertEquals(-1, plan.resolved.get(0).entry.chapterNo);
            assertEquals(EXTRA_TITLE, plan.resolved.get(0).entry.title);
            assertEquals(raw.raw, plan.resolved.get(0).entry.raw);

            PurchaseRow purchased = row(ME, 612, EXTRA_TITLE, 12, 0,
                    RemoteLedgerRecovery.date("2026-09-08"), WHO);
            VoucherLedger.Audit audit = SubscribedDetail.reconcile(WHO, ME, BOOK,
                    Arrays.asList(raw), Arrays.asList(extra), Arrays.asList(purchased), NOW);
            assertTrue(audit.message, audit.ok);
            assertTrue(audit.message, audit.checked);
            assertTrue(audit.message, audit.message.contains("第612章"));
        }
    }

    @Test
    public void unnumberedEntriesNeedVolumeEvidenceOnBothSidesEvenWithAUniqueTitle() {
        SubscribedDetail.Entry withVolume = SubscribedDetail.parseRow("番外 " + EXTRA_TITLE,
                "12", "代券", "2026-09-08");
        SubscribedDetail.Entry withoutVolume = SubscribedDetail.parseRow(EXTRA_TITLE,
                "12", "代券", "2026-09-08");
        assertFalse(RemoteLedgerRecovery.resolveAll("", Arrays.asList(withoutVolume),
                Arrays.asList(extra(6120, 612, "番外"))).ok);
        assertFalse(RemoteLedgerRecovery.resolveAll("", Arrays.asList(withVolume),
                Arrays.asList(extra(6120, 612, null))).ok);
        assertFalse(RemoteLedgerRecovery.resolveAll("", Arrays.asList(withVolume),
                Arrays.asList(extra(6120, 612, "另一卷"))).ok);
    }

    @Test
    public void unnumberedTitlesAreMatchedByVolumeAndRemainAmbiguousWithinTheSameVolume() {
        SubscribedDetail.Entry entry = new SubscribedDetail.Entry(-1, "番外", EXTRA_TITLE,
                12, "代券", "2026-09-08", "番外 " + EXTRA_TITLE);
        List<Chapter> differentVolumes = Arrays.asList(extra(6120, 612, "番外"),
                extra(6130, 613, "另一卷"));
        RemoteLedgerRecovery.Resolution resolved = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(entry), differentVolumes);
        assertTrue(resolved.message, resolved.ok);
        assertEquals(6120L, resolved.resolved.get(0).chapter.id);
        List<Chapter> sameVolume = Arrays.asList(extra(6120, 612, "番外"),
                extra(6130, 613, "【番外】"));
        RemoteLedgerRecovery.Resolution ambiguous = RemoteLedgerRecovery.resolveAll("",
                Arrays.asList(entry), sameVolume);
        assertFalse(ambiguous.message, ambiguous.ok);
        assertTrue(ambiguous.message, ambiguous.message.contains("2 条同卷同标题候选"));

        VoucherLedger.Audit audit = SubscribedDetail.reconcile(WHO, ME, BOOK,
                Arrays.asList(entry), sameVolume, Arrays.asList(row(ME, 612, EXTRA_TITLE, 12, 0,
                        RemoteLedgerRecovery.date("2026-09-08"), WHO)), NOW);
        assertTrue(audit.message, audit.ok);
        assertFalse(audit.message, audit.checked);
        assertTrue(audit.message, audit.message.contains("不能判断是否缺席"));
    }

    @Test
    public void anExtraWithUncertainMoneyStillCannotBeBackfilled() {
        for (String amount : new String[]{null, "", "读不到", "0"}) {
            RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                    Arrays.asList(SubscribedDetail.parseRow("番外 " + EXTRA_TITLE,
                            amount, "代券", "2026-09-08")),
                    Arrays.asList(extra(6120, 612, "番外")), new ArrayList<PurchaseRow>());
            assertFalse(plan.message, plan.ok);
            assertTrue(plan.purchases.isEmpty());
        }
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

    private static final class PageReader implements SubscribedDetail.DetailReader {
        private final Map<String, List<Selector>> selectors;
        private final List<FakeNode> pages;
        private int page;
        private long now;

        PageReader(Map<String, List<Selector>> selectors, FakeNode... pages) {
            this.selectors = selectors;
            this.pages = Arrays.asList(pages);
        }

        FakeNode currentRoot() {
            return pages.get(page);
        }

        Map<String, List<Selector>> selectors() {
            return selectors;
        }

        @Override public NodeView root() {
            return currentRoot();
        }

        @Override public List<NodeView> findAllIn(NodeView subtree, String key) {
            List<NodeView> out = new ArrayList<>();
            for (Selector selector : selectors.get(key)) {
                out.addAll(NodeMatcher.findAll(subtree, selector));
                if (!out.isEmpty()) break;
            }
            return out;
        }

        @Override public boolean scrollForward() {
            if (page + 1 >= pages.size()) return false;
            page++;
            return true;
        }

        @Override public void waitMillis(long millis) {
            now += millis;
        }

        @Override public long now() {
            return now;
        }
    }

    private static final class SettlingReader implements SubscribedDetail.DetailReader {
        private final Map<String, List<Selector>> selectors;
        private final List<FakeNode> roots;
        private int capture;
        private long now;

        SettlingReader(Map<String, List<Selector>> selectors, FakeNode... roots) {
            this.selectors = selectors;
            this.roots = Arrays.asList(roots);
        }

        @Override public NodeView root() {
            FakeNode root = roots.get(Math.min(capture, roots.size() - 1));
            capture++;
            return root;
        }

        @Override public List<NodeView> findAllIn(NodeView subtree, String key) {
            List<NodeView> out = new ArrayList<>();
            for (Selector selector : selectors.get(key)) {
                out.addAll(NodeMatcher.findAll(subtree, selector));
                if (!out.isEmpty()) break;
            }
            return out;
        }

        @Override public boolean scrollForward() {
            return false;
        }

        @Override public void waitMillis(long millis) {
            now += millis;
        }

        @Override public long now() {
            return now;
        }
    }

    private static final class RepeatingScrollReader implements SubscribedDetail.DetailReader {
        private final PageReader delegate;

        RepeatingScrollReader(Map<String, List<Selector>> selectors, FakeNode root) {
            delegate = new PageReader(selectors, root);
        }

        @Override public NodeView root() {
            return delegate.root();
        }

        @Override public List<NodeView> findAllIn(NodeView subtree, String key) {
            return delegate.findAllIn(subtree, key);
        }

        @Override public boolean scrollForward() {
            return true;
        }

        @Override public void waitMillis(long millis) {
            delegate.waitMillis(millis);
        }

        @Override public long now() {
            return delegate.now();
        }
    }

    private static final class DelayedScrollReader implements SubscribedDetail.DetailReader {
        private final Map<String, List<Selector>> selectors;
        private final FakeNode oldRoot;
        private final FakeNode newRoot;
        private int capturesAfterScroll;
        private boolean scrolled;
        private long now;

        DelayedScrollReader(Map<String, List<Selector>> selectors,
                            FakeNode oldRoot, FakeNode newRoot) {
            this.selectors = selectors;
            this.oldRoot = oldRoot;
            this.newRoot = newRoot;
        }

        @Override public NodeView root() {
            if (!scrolled || capturesAfterScroll++ < 3) return oldRoot;
            return newRoot;
        }

        @Override public List<NodeView> findAllIn(NodeView subtree, String key) {
            List<NodeView> out = new ArrayList<>();
            for (Selector selector : selectors.get(key)) {
                out.addAll(NodeMatcher.findAll(subtree, selector));
                if (!out.isEmpty()) break;
            }
            return out;
        }

        @Override public boolean scrollForward() {
            if (scrolled) return false;
            scrolled = true;
            return true;
        }

        @Override public void waitMillis(long millis) {
            now += millis;
        }

        @Override public long now() {
            return now;
        }
    }

    private static FakeNode transaction(String desc, String amount, int top) {
        return FakeNode.node().withBounds(0, top, 1080, top + 220).add(
                FakeNode.text(desc).withId("com.sfacg:id/tvDesc")
                        .withBounds(110, top + 20, 650, top + 130),
                FakeNode.text(amount).withBounds(803, top + 30, 917, top + 90),
                FakeNode.text("代券").withBounds(803, top + 100, 917, top + 160));
    }

    private static FakeNode date(String value, int top) {
        return FakeNode.text(value).withId("com.sfacg:id/tvTime")
                .withBounds(425, top, 655, top + 45);
    }

    @Test
    public void waitsForTwoMatchingCompleteSnapshots() throws Exception {
        FakeNode transientPage = FakeNode.node().add(date("2026-09-08", 200),
                transaction("星彩 第6章 薯片", "10", 260));
        FakeNode settled = FakeNode.node().add(date("2026-09-08", 200),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("铃兰花 第65章 薄暮", "12", 500));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new SettlingReader(bundled(), transientPage, settled, settled, settled));

        assertEquals(2, entries.size());
        assertEquals(6, entries.get(0).chapterNo);
        assertEquals(65, entries.get(1).chapterNo);
        assertEquals("2026-09-08", entries.get(1).date);
    }

    @Test
    public void settlingTimeoutRejectsAnUnstableRoot() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 200),
                transaction("星彩 第6章 薯片", "10", 260));
        List<FakeNode> roots = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            roots.add(FakeNode.node().add(date(String.format(java.util.Locale.ROOT,
                            "2026-09-%02d", i + 1), 200),
                    transaction("铃兰花 第" + (65 + i) + "章 临时", "12", 500)));
        }
        try {
            FakeNode[] snapshots = new FakeNode[roots.size() + 1];
            snapshots[0] = first;
            for (int i = 0; i < roots.size(); i++) snapshots[i + 1] = roots.get(i);
            SubscribedDetail.collect(new SettlingReader(bundled(), snapshots));
        } catch (StepRunner.StepFailure e) {
            assertEquals(StepRunner.Kind.TIMEOUT, e.kind);
            return;
        }
        throw new AssertionError("从未稳定的明细屏不能作为补账事实");
    }

    @Test
    public void aStableEmptyRootIsNotAcceptedAsACompleteLedger() throws Exception {
        FakeNode empty = FakeNode.node();
        FakeNode loaded = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 300));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new SettlingReader(bundled(), empty, empty, empty, loaded, loaded, loaded));

        assertEquals(1, entries.size());
        assertEquals(6, entries.get(0).chapterNo);
    }

    @Test
    public void postScrollWaitsUntilTheAccessibilityTreeLeavesTheOldScreen() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 300),
                transaction("铃兰花 第65章 薄暮", "12", 600));
        FakeNode second = FakeNode.node().add(date("2026-04-05", 100),
                transaction("铃兰花 第91章 花叶", "11", 300));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new DelayedScrollReader(bundled(), first, second));

        assertEquals(3, entries.size());
        assertEquals(91, entries.get(2).chapterNo);
        assertEquals("2026-04-05", entries.get(2).date);
    }

    @Test
    public void unchangedPostScrollScreenAddsNoDuplicateRows() throws Exception {
        FakeNode page = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 300),
                transaction("铃兰花 第65章 薄暮", "12", 600));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new RepeatingScrollReader(bundled(), page));

        assertEquals(2, entries.size());
    }

    @Test
    public void groupedDateAppliesToEveryFollowingCard() throws Exception {
        FakeNode page = FakeNode.node().add(date("2026-09-08", 200),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("铃兰花 第65章 薄暮", "12", 500),
                transaction("铃兰花 第92章 初次见面", "12", 740),
                date("2026-04-05", 980),
                transaction("铃兰花 第91章 花叶", "11", 1040));
        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), page));

        assertEquals(4, entries.size());
        assertEquals("2026-09-08", entries.get(0).date);
        assertEquals("2026-09-08", entries.get(1).date);
        assertEquals("2026-09-08", entries.get(2).date);
        assertEquals("2026-04-05", entries.get(3).date);
    }

    @Test
    public void groupedDatesUseCoordinatesNotTreeOrder() throws Exception {
        FakeNode page = FakeNode.node().add(
                transaction("铃兰花 第91章 花叶", "11", 1040),
                transaction("星彩 第6章 薯片", "10", 260),
                date("2026-04-05", 980),
                date("2026-09-08", 200));
        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), page));

        assertEquals(2, entries.size());
        assertEquals(6, entries.get(0).chapterNo);
        assertEquals("2026-09-08", entries.get(0).date);
        assertEquals(91, entries.get(1).chapterNo);
        assertEquals("2026-04-05", entries.get(1).date);
    }

    @Test
    public void anInvisibleDateCannotAuthorizeARecovery() throws Exception {
        FakeNode page = FakeNode.node().add(
                date("2026-09-08", 0).withBounds(0, 0, 0, 0),
                transaction("星彩 第6章 薯片", "10", 260));
        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), page));

        assertEquals(1, entries.size());
        assertNull(entries.get(0).date);
    }

    @Test
    public void anInvisibleNonzeroDateCannotAuthorizeARecovery() throws Exception {
        FakeNode page = FakeNode.node().add(
                date("2026-09-08", 100).visible(false),
                transaction("星彩 第6章 薯片", "10", 260));
        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), page));

        assertEquals(1, entries.size());
        assertNull(entries.get(0).date);
    }

    @Test
    public void conflictingCrossScreenDatesRemainTwoFacts() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 200),
                transaction("星彩 第6章 薯片", "10", 260));
        FakeNode second = FakeNode.node().add(date("2026-09-07", 120),
                transaction("星彩 第6章 薯片", "10", 260));
        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(2, entries.size());
        RemoteLedgerRecovery.Plan plan = RemoteLedgerRecovery.plan(WHO, BOOK, ME, 1,
                entries, Arrays.asList(chapter(990, 99, "6 薯片")),
                new ArrayList<PurchaseRow>());
        assertFalse(plan.message, plan.ok);
        assertTrue(plan.message, plan.message.contains("重复出现"));
    }

    @Test
    public void identicalCardAtTheSamePositionDoesNotProveScrollContinuity() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 200),
                transaction("星彩 第6章 薯片", "10", 260));
        FakeNode samePosition = FakeNode.node().add(
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("铃兰花 第65章 薄暮", "12", 500));
        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, samePosition));

        assertEquals(3, entries.size());
        assertEquals(6, entries.get(0).chapterNo);
        assertEquals(6, entries.get(1).chapterNo);
        assertNull("相同位置的同文卡片不能证明列表真的滚动过", entries.get(1).date);
        assertNull(entries.get(2).date);
    }

    @Test
    public void realSevenPlusThreeHeaderlessOverlapsRemainSevenFacts() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-12", 80),
                transaction("铃兰花 第83章 最后", "11", 140),
                date("2026-09-08", 380),
                transaction("星彩 第6章 薯片", "10", 440),
                transaction("铃兰花 第65章 薄暮", "12", 680),
                transaction("铃兰花 第92章 初次见面", "12", 920),
                date("2026-04-05", 1160),
                transaction("铃兰花 第91章 花叶", "11", 1220),
                date("2026-04-03", 1460),
                transaction("铃兰花 第81章 沙滩", "12", 1520),
                date("2026-03-30", 1760),
                transaction("铃兰花 第71章 前夜", "10", 1820));
        FakeNode second = FakeNode.node().add(
                transaction("星彩 第6章 薯片", "10", 180),
                transaction("铃兰花 第65章 薄暮", "12", 430),
                transaction("铃兰花 第92章 初次见面", "12", 670));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(7, entries.size());
        for (SubscribedDetail.Entry entry : entries) assertNotNull(entry.date);
    }

    @Test
    public void inheritedDateOnlyAppliesToTheProvenOverlappingRows() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("铃兰花 第65章 薄暮", "12", 500));
        FakeNode second = FakeNode.node().add(
                transaction("星彩 第6章 薯片", "10", 200),
                transaction("铃兰花 第65章 薄暮", "12", 440),
                transaction("铃兰花 第92章 初次见面", "12", 680));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        SubscribedDetail.Entry newRow = null;
        for (SubscribedDetail.Entry entry : entries) {
            if (entry.chapterNo == 92) newRow = entry;
        }
        assertNotNull(newRow);
        assertNull("新出现的卡片没有直接跨屏日期证据，不能继承旧组日期", newRow.date);
    }

    @Test
    public void blankVisibleDateHeaderBlocksCrossScreenInheritance() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("铃兰花 第65章 薄暮", "12", 500));
        FakeNode second = FakeNode.node().add(
                FakeNode.text("   ").withId("com.sfacg:id/tvTime")
                        .withBounds(425, 100, 655, 145),
                transaction("星彩 第6章 薯片", "10", 200),
                transaction("铃兰花 第65章 薄暮", "12", 440));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(4, entries.size());
        assertNull("可见但读不出文字的日期标题不能继承上一屏日期", entries.get(2).date);
        assertNull(entries.get(3).date);
    }

    @Test
    public void carriedDateNeedsTwoStableOverlappingTransactions() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("铃兰花 第65章 薄暮", "12", 500));
        FakeNode overlap = FakeNode.node().add(
                transaction("星彩 第6章 薯片", "10", 200),
                transaction("铃兰花 第65章 薄暮", "12", 440),
                transaction("铃兰花 第92章 初次见面", "12", 680));
        List<SubscribedDetail.Entry> continuous = SubscribedDetail.collect(
                new PageReader(bundled(), first, overlap));
        assertEquals(3, continuous.size());
        assertEquals(92, continuous.get(2).chapterNo);
        assertNull(continuous.get(2).date);

        FakeNode onlyOneAnchor = FakeNode.node().add(
                transaction("铃兰花 第65章 薄暮", "12", 440),
                transaction("铃兰花 第92章 初次见面", "12", 680));
        List<SubscribedDetail.Entry> uncertain = SubscribedDetail.collect(
                new PageReader(bundled(), first, onlyOneAnchor));
        assertEquals(4, uncertain.size());
        assertNull("只有一条同文卡片不能证明它就是上一屏同一笔交易", uncertain.get(2).date);
        assertNull(uncertain.get(3).date);
    }

    @Test
    public void twoOrderedAnchorsMayMoveBySlightlyDifferentDistances() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 300),
                transaction("铃兰花 第65章 薄暮", "12", 600));
        FakeNode second = FakeNode.node().add(
                transaction("星彩 第6章 薯片", "10", 180),
                transaction("铃兰花 第65章 薄暮", "12", 470));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(2, entries.size());
        assertEquals("2026-09-08", entries.get(0).date);
        assertEquals("2026-09-08", entries.get(1).date);
    }

    @Test
    public void repeatedIdentityCannotProveCrossScreenContinuity() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("星彩 第6章 薯片", "10", 500),
                transaction("铃兰花 第65章 薄暮", "12", 740));
        FakeNode second = FakeNode.node().add(
                transaction("星彩 第6章 薯片", "10", 200),
                transaction("铃兰花 第65章 薄暮", "12", 440));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(5, entries.size());
        assertNull(entries.get(3).date);
        assertNull(entries.get(4).date);
    }

    @Test
    public void reversedRowsCannotProveCrossScreenContinuity() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 300),
                transaction("铃兰花 第65章 薄暮", "12", 600));
        FakeNode second = FakeNode.node().add(
                transaction("铃兰花 第65章 薄暮", "12", 180),
                transaction("星彩 第6章 薯片", "10", 470));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(4, entries.size());
        assertNull(entries.get(2).date);
        assertNull(entries.get(3).date);
    }

    @Test
    public void overlapUsesOnlyTheActuallyMatchedPrefix() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 80),
                transaction("A卷 第1章 A", "10", 300),
                transaction("A卷 第2章 B", "10", 600),
                transaction("A卷 第3章 C", "10", 900),
                date("2026-04-05", 1160),
                transaction("A卷 第4章 D", "10", 1220));
        FakeNode second = FakeNode.node().add(
                transaction("A卷 第2章 B", "10", 180),
                transaction("A卷 第3章 C", "10", 470),
                transaction("A卷 第5章 X", "10", 760));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(5, entries.size());
        SubscribedDetail.Entry x = null;
        for (SubscribedDetail.Entry entry : entries) if (entry.chapterNo == 5) x = entry;
        assertNotNull(x);
        assertNull("只匹配 B/C；新卡 X 不能冒充旧卡 D 并继承其日期", x.date);
    }

    @Test
    public void inheritedDatesDoNotRelayToANewRowOnTheNextScreen() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("A卷 第1章 A", "10", 260),
                transaction("A卷 第2章 B", "10", 500));
        FakeNode second = FakeNode.node().add(
                transaction("A卷 第1章 A", "10", 200),
                transaction("A卷 第2章 B", "10", 440),
                transaction("A卷 第3章 X", "10", 680));
        FakeNode third = FakeNode.node().add(
                transaction("A卷 第2章 B", "10", 180),
                transaction("A卷 第3章 X", "10", 420));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second, third));

        SubscribedDetail.Entry x = null;
        for (SubscribedDetail.Entry entry : entries) if (entry.chapterNo == 3) x = entry;
        assertNotNull(x);
        assertNull("X 首次出现时没有日期；下一屏也不能借 B 的旧日期污染它", x.date);
    }

    @Test
    public void directHeaderSurvivesTheOverlapBoundaryForNewRows() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 80),
                transaction("A卷 第1章 A", "10", 300),
                transaction("A卷 第2章 B", "10", 600));
        FakeNode second = FakeNode.node().add(date("2026-04-05", 100),
                transaction("A卷 第1章 A", "10", 200),
                transaction("A卷 第2章 B", "10", 440),
                transaction("A卷 第3章 C", "10", 680));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(5, entries.size());
        assertEquals("2026-04-05", entries.get(4).date);
    }

    @Test
    public void aHeaderInsideTheOverlapPreservesConflictingFacts() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("A卷 第1章 A", "10", 300),
                transaction("A卷 第2章 B", "10", 600),
                transaction("A卷 第3章 C", "10", 900));
        FakeNode second = FakeNode.node().add(
                transaction("A卷 第1章 A", "10", 180),
                date("2026-04-05", 400),
                transaction("A卷 第2章 B", "10", 470),
                transaction("A卷 第3章 C", "10", 760));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(5, entries.size());
        assertEquals("2026-04-05", entries.get(3).date);
        assertEquals("2026-04-05", entries.get(4).date);
    }

    @Test
    public void repeatedCurrentHeaderStillDeduplicatesTheSameMovedCards() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("A卷 第1章 A", "10", 300),
                transaction("A卷 第2章 B", "10", 600));
        FakeNode second = FakeNode.node().add(date("2026-09-08", 80),
                transaction("A卷 第1章 A", "10", 180),
                transaction("A卷 第2章 B", "10", 470));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second));

        assertEquals(2, entries.size());
    }

    @Test
    public void identicalFactsOnTheSameScreenAreNotCollapsed() throws Exception {
        FakeNode page = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("星彩 第6章 薯片", "10", 500));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), page));

        assertEquals(2, entries.size());
    }

    @Test
    public void inheritedEvidenceMayFollowOnlyTheSameProvenCard() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 300),
                transaction("铃兰花 第65章 薄暮", "12", 600),
                transaction("铃兰花 第92章 初次见面", "12", 900));
        FakeNode second = FakeNode.node().add(
                transaction("铃兰花 第65章 薄暮", "12", 300),
                transaction("铃兰花 第92章 初次见面", "12", 600));
        FakeNode third = FakeNode.node().add(
                transaction("铃兰花 第65章 薄暮", "12", 180),
                transaction("铃兰花 第92章 初次见面", "12", 470));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second, third));

        assertEquals(3, entries.size());
        assertEquals("2026-09-08", entries.get(1).date);
        assertEquals("2026-09-08", entries.get(2).date);
    }

    @Test
    public void newRowsStillCannotReceiveAProvenDateAcrossTwoScreens() throws Exception {
        FakeNode first = FakeNode.node().add(date("2026-09-08", 100),
                transaction("星彩 第6章 薯片", "10", 260),
                transaction("铃兰花 第65章 薄暮", "12", 500));
        FakeNode second = FakeNode.node().add(
                transaction("星彩 第6章 薯片", "10", 200),
                transaction("铃兰花 第65章 薄暮", "12", 440),
                transaction("铃兰花 第92章 初次见面", "12", 680));
        FakeNode third = FakeNode.node().add(
                transaction("铃兰花 第65章 薄暮", "12", 200),
                transaction("铃兰花 第92章 初次见面", "12", 440),
                transaction("铃兰花 第91章 花叶", "11", 680));

        List<SubscribedDetail.Entry> entries = SubscribedDetail.collect(
                new PageReader(bundled(), first, second, third));

        SubscribedDetail.Entry flower = null;
        for (SubscribedDetail.Entry entry : entries) {
            if (entry.chapterNo == 91) flower = entry;
        }
        assertNotNull(flower);
        assertNull("新出现的卡片不是重叠段的一部分，不能获得中继日期", flower.date);
    }

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
