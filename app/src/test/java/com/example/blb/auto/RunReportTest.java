package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 跑完那个弹窗上的文案（{@link RunReport}）。
 *
 * <p>为什么这几行值得写测试：这个弹窗是使用者<b>唯一读得到</b>的结果。他手指动不了 ——
 * 拉不开通知栏、点不进「运行日志」，所以这里少说一句、或者多说一句猜出来的原因，
 * 对他就是「不知道发生了什么」或者「被引到错的方向去」。
 */
public class RunReportTest {

    private static CheckInQueue.Summary checkIn(int ok, int already, int failed, int total) {
        CheckInQueue.Summary s = new CheckInQueue.Summary();
        s.ok = ok;
        s.already = already;
        s.failed = failed;
        s.total = total;
        return s;
    }

    // ---------- 只签到 ----------

    @Test
    public void aCleanCheckInSaysHowManyOutOfHowMany() {
        RunReport r = RunReport.ofCheckIn(checkIn(6, 2, 0, 8));
        assertEquals("签到完成", r.title);
        assertTrue(r.allGood);
        assertEquals("已签到 8/8 个号", r.body());
    }

    /**
     * 没签成的号必须<b>点名</b>。「失败 2」对他等于什么都没说：他打不开「今日状态」
     * 那一屏去看是哪个号，也没法自己补签。
     */
    @Test
    public void failedAccountsAreNamed() {
        CheckInQueue.Summary s = checkIn(5, 1, 2, 8);
        s.failedNames.add("iierr89");
        s.failedNames.add("好好上课是");
        RunReport r = RunReport.ofCheckIn(s);
        assertEquals("签到没全成", r.title);
        assertFalse(r.allGood);
        assertTrue(r.body(), r.body().contains("没签成的：iierr89、好好上课是"));
    }

    /** 名字太多就报个数：弹窗要一眼读完，不是让人在上面翻。 */
    @Test
    public void tooManyNamesFallBackToACount() {
        CheckInQueue.Summary s = checkIn(3, 0, 5, 8);
        for (String name : new String[]{"甲", "乙", "丙", "丁", "戊"}) s.failedNames.add(name);
        String body = RunReport.ofCheckIn(s).body();
        assertTrue(body, body.contains("甲、乙、丙"));
        assertTrue(body, body.contains("等 5 个"));
        assertFalse("第四个不该出现在弹窗上", body.contains("丁"));
    }

    /** 只有个数、没有名单（老日志、或者名单没填）也不能什么都不说。 */
    @Test
    public void aCountWithoutNamesStillGetsReported() {
        String body = RunReport.ofCheckIn(checkIn(6, 0, 2, 8)).body();
        assertTrue(body, body.contains("没签成的 2 个"));
    }

    /** 中止的原因必须在弹窗上，那是他唯一能知道「为什么停了」的地方。 */
    @Test
    public void anAbortReasonIsAlwaysShown() {
        CheckInQueue.Summary s = checkIn(3, 0, 0, 8);
        s.abortReason = "撞上验证码，等你处理";
        RunReport r = RunReport.ofCheckIn(s);
        assertFalse(r.allGood);
        assertTrue(r.body(), r.body().contains("整趟停在这里：撞上验证码，等你处理"));
    }

    @Test
    public void noEnabledAccountSaysSo() {
        assertTrue(RunReport.ofCheckIn(checkIn(0, 0, 0, 0)).body()
                .contains("没有启用的账号"));
    }

    @Test
    public void pendingAdsAreNamedToo() {
        CheckInQueue.Summary s = checkIn(8, 0, 0, 8);
        s.adPending.add("五杯半雪碧");
        assertTrue(RunReport.ofCheckIn(s).body().contains("还有广告没领：五杯半雪碧"));
    }

    // ---------- 每日流程 ----------

    @Test
    public void aDailyRunReportsCheckInAdsAndSubscription() {
        DailyQueue.Summary s = new DailyQueue.Summary();
        s.total = 8;
        s.checkedIn = 8;
        s.adsWatched = 5;
        s.subscribing = true;
        s.bought = 1;
        s.spent = 20;
        RunReport r = RunReport.ofDaily(s);
        assertEquals("今天的流程跑完了", r.title);
        assertTrue(r.allGood);
        assertTrue(r.body(), r.body().contains("已签到 8/8 个号"));
        assertTrue(r.body(), r.body().contains("广告看完 5 个"));
        assertTrue(r.body(), r.body().contains("订到 1 章，花 20 代券"));
    }

    /**
     * 一章都没订到的时候<b>绝不</b>替它猜原因。代券不够、目录对不上、撞验证码，
     * 在这一层看着都一样；猜错会把他引到错的地方去。真原因由下面的中止原因那一行说。
     */
    @Test
    public void zeroChaptersNeverGuessesWhy() {
        DailyQueue.Summary s = new DailyQueue.Summary();
        s.total = 8;
        s.alreadySigned = 8;
        s.subscribing = true;
        String body = RunReport.ofDaily(s).body();
        assertTrue(body, body.contains("一章都没订到"));
        assertFalse("不许猜原因", body.contains("不够"));
        assertFalse("不许猜原因", body.contains("代券"));
    }

    /** 这一轮压根没跑订阅（没设目标小说／选择器缺 key）—— 那是另一件事，得说清楚。 */
    @Test
    public void aSkippedSubscriptionSaysWhy() {
        DailyQueue.Summary s = new DailyQueue.Summary();
        s.total = 8;
        s.alreadySigned = 8;
        s.subscribeNote = "还没设定集中订阅的目标小说，这轮只签到和看广告";
        String body = RunReport.ofDaily(s).body();
        assertTrue(body, body.contains("这一趟没订阅：还没设定集中订阅的目标小说"));
    }

    /**
     * 「整本停在第83章」要弹出来，但它<b>不算失败</b>：8 个号都只是代券不够的时候，
     * 那是护栏在正常工作（用户的原话：「如果余额不够就放弃，切换下一个账号尝试」），
     * 流程本身是跑完的。真出错由「订阅没走通 N 个号」那一行说。
     */
    @Test
    public void beingStuckOnMoneyIsReportedButIsNotAFailure() {
        DailyQueue.Summary s = new DailyQueue.Summary();
        s.total = 8;
        s.checkedIn = 8;
        s.subscribing = true;
        s.stuckNote = "第83章没订下来（8 个号都试过了），整本停在这里 —— 后面的章一章都没往后买"
                + "。最后一个号：好好上课是 只剩 2 代券，不够买第83章（约 20 代券），换下一个号";
        RunReport r = RunReport.ofDaily(s);
        assertEquals("今天的流程跑完了", r.title);
        assertTrue("代券不够不是出错", r.allGood);
        assertTrue(r.body(), r.body().contains("第83章"));
        assertTrue("必须说清后面的章没有被跳过去买", r.body().contains("没往后买"));
    }

    /**
     * 万一真越过了一章，那句话必须弹出来、还要<b>点名到章</b>，并且算没跑完。
     *
     * <p>2026-09-03 那趟 6 章全买成了，弹窗于是写着「今天的流程跑完了」，可第83章是空的：
     * 84、85、86、87 章照样往下买，缺口自己不会浮出来。使用者是自己对着 STATUS 广播
     * 才发现的 —— 而「不漏订、8 个号拼出完整一本」是他两条硬约束之一，缺一章就不算跑完。
     */
    @Test
    public void aMissingChapterIsNamedAndBreaksTheGoodMood() {
        DailyQueue.Summary s = new DailyQueue.Summary();
        s.total = 8;
        s.checkedIn = 8;
        s.subscribing = true;
        s.bought = 6;
        s.spent = 110;
        s.gapNote = "这趟买到了第87章，可第83章还是空的 —— 中间漏了一章，这本书现在拼不完整";
        RunReport r = RunReport.ofDaily(s);
        assertEquals("今天的流程没跑完", r.title);
        assertFalse("缺一章就不算跑完", r.allGood);
        assertTrue(r.body(), r.body().contains("第83章"));
        assertTrue("订到几章还是要照说", r.body().contains("订到 6 章"));
    }

    /**
     * 对账细节（{@code notes}）<b>不许</b>进弹窗。
     *
     * <p>「22 章在这台手机上已下载、但账本里没有归属（第49章、第53章…）」这类话一条就能
     * 占满整个弹窗，把「签到成了几个」挤到看不见的地方。它们留在运行日志里给排查用。
     */
    @Test
    public void reconciliationNotesStayOutOfThePopup() {
        DailyQueue.Summary s = new DailyQueue.Summary();
        s.total = 8;
        s.checkedIn = 8;
        s.notes.add("22 章在这台手机上已下载、但账本里没有归属（第49章、第53章…）");
        assertFalse(RunReport.ofDaily(s).body(), RunReport.ofDaily(s).body().contains("已下载"));

        SubscribeQueue.Summary sub = new SubscribeQueue.Summary();
        sub.total = 8;
        sub.notes.add("wefeef：逐章都对上了（第56章、第70章、第71章，共 3 章，全是代券）");
        assertFalse(RunReport.ofSubscribe(sub).body().contains("逐章"));
    }

    // ---------- 只订阅 ----------

    @Test
    public void aSubscribeRunSaysWhatItBought() {
        SubscribeQueue.Summary s = new SubscribeQueue.Summary();
        s.total = 8;
        s.bought = 3;
        s.spent = 60;
        RunReport r = RunReport.ofSubscribe(s);
        assertEquals("订阅完成", r.title);
        assertTrue(r.allGood);
        assertTrue(r.body(), r.body().contains("订到 3 章，花 60 代券"));
        assertTrue(r.body(), r.body().contains("8 个号都试过了"));
    }

    /** 8 个号都买不起（今天最常见的收工方式）：说「一章都没订到」，不当失败。 */
    @Test
    public void everyAccountTooPoorIsNotAFailure() {
        SubscribeQueue.Summary s = new SubscribeQueue.Summary();
        s.total = 8;
        RunReport r = RunReport.ofSubscribe(s);
        assertTrue(r.allGood);
        assertTrue(r.body(), r.body().contains("一章都没订到"));
    }

    /** 点过「立即下载」但结果不明那种中止，钱可能已经扣了 —— 这句话必须弹出来。 */
    @Test
    public void anAbortedSubscribeRunIsNotGood() {
        SubscribeQueue.Summary s = new SubscribeQueue.Summary();
        s.total = 8;
        s.bought = 1;
        s.spent = 20;
        s.abortReason = "点了立即下载但没看出买成没买成，先停下";
        RunReport r = RunReport.ofSubscribe(s);
        assertEquals("订阅没做完", r.title);
        assertFalse(r.allGood);
        assertTrue(r.body(), r.body().contains("整趟停在这里：点了立即下载"));
    }

    /** 只订阅那一趟同样：买到 3 章不等于没漏，越过一章那句话得跟着弹出来。 */
    @Test
    public void aSubscribeRunWithAHoleIsNotGoodEither() {
        SubscribeQueue.Summary s = new SubscribeQueue.Summary();
        s.total = 8;
        s.bought = 3;
        s.spent = 60;
        s.gapNote = "这趟买到了第87章，可第83章还是空的 —— 中间漏了一章，这本书现在拼不完整";
        RunReport r = RunReport.ofSubscribe(s);
        assertEquals("订阅没做完", r.title);
        assertFalse(r.allGood);
        assertTrue(r.body(), r.body().contains("第83章"));
    }

    // ---------- 切号、跑都没跑起来 ----------

    @Test
    public void switchingAccountsShowsTheOneLineItGotBack() {
        RunReport r = RunReport.ofSwitch("已经登着「五杯半雪碧」了，什么都不用做");
        assertEquals("切号完成", r.title);
        assertEquals("已经登着「五杯半雪碧」了，什么都不用做", r.body());
    }

    @Test
    public void aQueueThatNeverStartedSaysSo() {
        RunReport r = RunReport.failed("签到", "无障碍服务没连上，先去系统设置里打开「blb自动签到」");
        assertEquals("签到没跑起来", r.title);
        assertFalse(r.allGood);
        assertTrue(r.body(), r.body().contains("无障碍服务没连上"));
    }

    /** 连 Summary 都没拿到（队列自己崩了）也得弹一句人话，不能是空白弹窗。 */
    @Test
    public void aNullSummaryStillProducesSomethingReadable() {
        for (RunReport r : new RunReport[]{RunReport.ofCheckIn(null), RunReport.ofDaily(null),
                RunReport.ofSubscribe(null), RunReport.ofSwitch(null), RunReport.failed("签到", "")}) {
            assertFalse(r.title.isEmpty());
            assertFalse(r.body().trim().isEmpty());
        }
    }
}
