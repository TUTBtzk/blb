package com.example.blb.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.Keys;
import com.example.blb.auto.SelectorSet;
import com.example.blb.auto.SubscribeRun;
import com.example.blb.data.Account;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Chapter;
import com.example.blb.data.CheckInLog;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.ui.DetailActivity;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;
import com.example.blb.work.DailyScheduler;

import java.util.List;

/**
 * 不用碰屏幕的总控（<b>只存在于 debug 构建</b>）：开关每日定时、当场跑一趟、
 * 一条命令问出「现在到底怎么样了」。
 *
 * <p>为什么它是这个 App 的必需件而不是调试玩具：使用者是渐冻症患者，手指动不了。
 * 「每日自动签到」是设置页上一颗开关、「跑今天的流程」是首页一颗按钮 —— 这两样他都按不了。
 * 也就是说在有这个接收器之前，这个 App 只能靠旁人替他按才跑得起来。adb 广播是他唯一按得动的按钮。
 *
 * <p>它<b>不碰账本</b>：只写 SharedPreferences（开关）和启动队列，一条购买记录都不动。
 *
 * <pre>
 * # 现在怎么样了（只读，什么都不改）
 * adb shell am broadcast -a com.example.blb.debug.STATUS -p com.example.blb
 *
 * # 每日定时：开、关、改时间（跟设置页那颗开关是同一份状态）
 * adb shell am broadcast -a com.example.blb.debug.SCHEDULE -p com.example.blb --ez on true
 * adb shell am broadcast -a com.example.blb.debug.SCHEDULE -p com.example.blb --ez on true --ei hour 9
 * adb shell am broadcast -a com.example.blb.debug.SCHEDULE -p com.example.blb --ez on false
 *
 * # 立刻跑一趟（不等定时）：整套流程／只签到
 * adb shell am broadcast -a com.example.blb.debug.DAILY_NOW -p com.example.blb
 * adb shell am broadcast -a com.example.blb.debug.CHECKIN_NOW -p com.example.blb
 * adb logcat -s BlbControl:* BlbAuto:*
 *
 * # 把某一个二级页面拉到前台看（他看得见、只是按不动）
 * adb shell am broadcast -a com.example.blb.debug.SHOW_PAGE -p com.example.blb --es page log
 * #   page = notice｜today｜log｜stats｜chapters，不带就默认 log
 *
 * # 切到某个号（＝章节列表点一下那一行）：按 id，或者「买过目标书第 N 章的那个号」
 * adb shell am broadcast -a com.example.blb.debug.SWITCH_TO -p com.example.blb --el id 3
 * adb shell am broadcast -a com.example.blb.debug.SWITCH_TO -p com.example.blb --ei chapter 48
 * </pre>
 */
public class ControlReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbControl";

    private static final String ACTION_STATUS = "com.example.blb.debug.STATUS";
    private static final String ACTION_SCHEDULE = "com.example.blb.debug.SCHEDULE";
    private static final String ACTION_DAILY_NOW = "com.example.blb.debug.DAILY_NOW";
    private static final String ACTION_CHECKIN_NOW = "com.example.blb.debug.CHECKIN_NOW";
    private static final String ACTION_SHOW_PAGE = "com.example.blb.debug.SHOW_PAGE";
    private static final String ACTION_SWITCH_TO = "com.example.blb.debug.SWITCH_TO";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (ACTION_STATUS.equals(action)) status(app);
                else if (ACTION_SCHEDULE.equals(action)) schedule(app, intent);
                else if (ACTION_DAILY_NOW.equals(action)) runNow(app, true);
                else if (ACTION_CHECKIN_NOW.equals(action)) runNow(app, false);
                else if (ACTION_SHOW_PAGE.equals(action)) showPage(app, intent);
                else if (ACTION_SWITCH_TO.equals(action)) switchTo(app, intent);
                else Log.w(TAG, "不认识的动作：" + action);
            } catch (Throwable t) {
                Log.e(TAG, "总控失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-control").start();
    }

    // ---------- 把某一页拉到前台 ----------

    /**
     * 打开一个二级页面。
     *
     * <p>为什么这条命令是必需的：今日状态、运行日志、各账号累计、已登记章节、辅助点击说明
     * 这五样各占一整屏（原来它们在一级页面上被挤成两行／半屏／120dp），可要看得点一下 ——
     * 用户手指动不了，点不了。他看得见屏幕，所以「替他把某一页打开」正好补上这一环。
     */
    private void showPage(Context app, Intent intent) {
        String page = intent.getStringExtra("page");
        if (Texts.isBlank(page)) page = DetailActivity.PAGE_LOG;
        if (!DetailActivity.isKnownPage(page)) {
            Log.w(TAG, "不认识的页面名「" + page + "」。认得的是：" + DetailActivity.pageNames());
            return;
        }
        app.startActivity(DetailActivity.intent(app, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        Log.i(TAG, "已经请系统把「" + page + "」这一页拉到前台。"
                + "小米／红米要允许本 App「后台弹出界面」，否则这一下会静静地不生效"
                + "（设置页那段电池提示里要求过）。");
    }

    // ---------- 切到某个号 ----------

    /**
     * 退出现在登着的号、登入指定的那个号 —— 和章节列表点一下那一行走的是同一条路
     * （{@link AutomationService#startSwitchAccount}）。
     *
     * <p>为什么这条命令也是必需的：那一行他点不了。{@code --ei chapter 48} 是他真正会用的
     * 形式（「我要看第48章」），由账本回答「这一章是谁买的」，不用他自己去对照。
     */
    private void switchTo(Context app, Intent intent) {
        long id = intent.getLongExtra("id", 0);
        int chapterNo = intent.getIntExtra("chapter", 0);
        if (id <= 0 && chapterNo > 0) {
            id = buyerOfChapter(app, chapterNo);
            if (id <= 0) return;
        }
        if (id <= 0) {
            Log.w(TAG, "要指定切到哪个号：--el id <账号id>，或者 --ei chapter <章号>"
                    + "（按账本里那一章的买家切）。账号 id 用 STATUS 那条命令看。");
            return;
        }
        Account account = Db.get(app).accountDao().byId(id);
        if (account == null) {
            Log.w(TAG, "没有 id=" + id + " 这个账号（用 STATUS 看现有的 id）");
            return;
        }
        if (!BlbAccessibilityService.isReady()) {
            Log.w(TAG, "无障碍服务没连上，切不了号（重装会把它踢掉，要用 adb 重新打开）");
            return;
        }
        if (AutomationBus.isRunning()) {
            Log.w(TAG, "已经有一趟在跑了，这次什么都不做（免得两趟互相抢界面）");
            return;
        }
        Log.i(TAG, "开始切到「" + account.displayName() + "」（" + account.loginKindLabel()
                + "）：退出当前账号再登它。屏幕要亮着且已解锁；"
                + "碰上验证码会停下等人（绝不自动过验证码）。进度看 adb logcat -s BlbAuto");
        AutomationService.startSwitchAccount(app, id);
    }

    /** 账本里买过目标书第 N 章的那个号。答不出就说清为什么，绝不瞎切一个号。 */
    private long buyerOfChapter(Context app, int chapterNo) {
        SubscriptionDao subs = Db.get(app).subscriptionDao();
        Novel novel = SubscribeRun.resolveTarget(subs, null);
        if (novel == null) {
            Log.w(TAG, "没有集中订阅目标那本书，答不出第" + chapterNo + "章是谁买的");
            return 0;
        }
        Chapter chapter = subs.chapterByNo(novel.id, chapterNo);
        if (chapter == null) {
            Log.w(TAG, "《" + novel.title + "》账本里没登记第" + chapterNo + "章");
            return 0;
        }
        long found = 0;
        int devices = 0;
        for (Purchase p : subs.loadPurchasesOfNovel(novel.id)) {
            if (p.chapterId != chapter.id) continue;
            // OWNED ＝「本机显示已拥有」，8 个号共用、答不出买家。拿它当买家去切号，
            // 等于随便挑一个号退登重登，白挨一次验证码。
            if (Purchase.SRC_OWNED.equals(p.source)) devices++;
            else if (found == 0) found = p.accountId;
            else {
                Log.w(TAG, "第" + chapterNo + "章有多个号买过（账本里是要修的错），"
                        + "这条命令不替你猜；用 --el id 指名要切哪个");
                return 0;
            }
        }
        if (found > 0) return found;
        Log.w(TAG, "第" + chapterNo + "章账本里没有买家"
                + (devices > 0 ? "，只有 " + devices + " 条「本机显示已拥有」（答不出是谁买的）" : ""));
        return 0;
    }

    // ---------- 每日定时 ----------

    /**
     * 开关每日定时并可改时间。改完立刻重新登记 WorkManager 的周期任务
     * （{@link DailyScheduler#apply}），和设置页那颗开关是同一份状态、同一条路。
     */
    private void schedule(Context app, Intent intent) {
        if (intent.hasExtra("hour")) {
            int hour = intent.getIntExtra("hour", 9);
            Prefs.setDailyHour(app, hour);
            Log.i(TAG, "每日流程时间改成 " + Prefs.dailyHour(app) + " 点（整点错开 5 分钟跑）");
        }
        if (intent.hasExtra("on")) {
            boolean on = intent.getBooleanExtra("on", false);
            Prefs.setDailyEnabled(app, on);
            Log.i(TAG, on ? "每日流程已开启" : "每日流程已关闭");
        }
        DailyScheduler.apply(app);
        Log.i(TAG, describeSchedule(app));
        if (Prefs.isDailyEnabled(app) && !BlbAccessibilityService.isReady()) {
            Log.w(TAG, "但无障碍服务现在没连上 —— 到点也跑不了。"
                    + "重装 App 会把它踢掉，必须用 adb 重新打开并确认 Bound。");
        }
    }

    private static String describeSchedule(Context app) {
        if (!Prefs.isDailyEnabled(app)) {
            return "每日流程：关（到点什么都不会发生；开它用 --ez on true）";
        }
        long minutes = DailyScheduler.delayToNextRunMinutes(Prefs.dailyHour(app));
        return "每日流程：开，每天 " + Prefs.dailyHour(app) + ":05 跑一趟；"
                + "下一趟大约在 " + minutes / 60 + " 小时 " + minutes % 60 + " 分钟后";
    }

    // ---------- 立刻跑一趟 ----------

    /**
     * 不等定时，现在就跑。
     *
     * <p>走的是 {@link AutomationService}，和首页那颗按钮完全同一条路 —— 包括整趟点着屏幕
     * （无障碍的点击只对亮着的屏幕有效）和被系统杀掉后自动接着跑的那套。
     */
    private void runNow(Context app, boolean daily) {
        if (!BlbAccessibilityService.isReady()) {
            Log.w(TAG, "无障碍服务没连上，跑不了。用 adb 重新打开它（重装会把它踢掉）：\n"
                    + "  adb shell settings put secure enabled_accessibility_services "
                    + "com.example.blb/com.example.blb.auto.BlbAccessibilityService\n"
                    + "  adb shell settings put secure accessibility_enabled 1");
            return;
        }
        if (AutomationBus.isRunning()) {
            Log.w(TAG, "已经有一趟在跑了，这次什么都不做（免得两趟互相抢界面）");
            return;
        }
        if (daily) {
            Log.i(TAG, "开始跑整套流程（签到 → 广告 → 券够就订阅）。"
                    + "屏幕要亮着且已解锁；进度看 adb logcat -s BlbAuto");
            AutomationService.startDaily(app);
        } else {
            Log.i(TAG, "开始跑签到队列（只签到，不看广告不订阅）");
            AutomationService.startCheckIn(app);
        }
    }

    // ---------- 现在怎么样了 ----------

    /** 一条命令回答「这个 App 现在准备好了吗、今天做了什么、下一步会做什么」。只读。 */
    private void status(Context app) {
        Log.i(TAG, "===== 现在的状态（" + Texts.todayYmd() + "）=====");
        Log.i(TAG, "无障碍服务：" + (BlbAccessibilityService.isReady()
                ? "已连上（能读能点）" : "没连上 —— 什么都跑不了，要用 adb 重新打开"));
        Log.i(TAG, describeSchedule(app));
        Log.i(TAG, "队列：" + (AutomationBus.isRunning() ? "正在跑" : "空闲"));

        SelectorSet selectors = SelectorSet.load(app);
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SUBSCRIBE);
        Log.i(TAG, "选择器：来自 " + selectors.source() + "，共 " + selectors.keys().size() + " 个 key；"
                + (missing.isEmpty() ? "订阅需要的都配齐了"
                : "订阅还缺 " + android.text.TextUtils.join("、", missing)));

        Log.i(TAG, "花钱这一档：按队列顺序真的订阅 —— 一个号订到代券不够下一章就换下一个号，"
                + "不限章数（实付里出现火券一律当买不起，绝不替你花火券）");
        Log.i(TAG, "每号每日代券上限：" + (Prefs.dailySpendCap(app) == 0
                ? "不限" : Prefs.dailySpendCap(app) + " 代券"));
        Log.i(TAG, "广告：每号每天 " + Prefs.adsPerAccount(app) + " 个"
                + (Prefs.isAdAssist(app) ? "，按键由脚本替你按（视频照原速播给你看）"
                : "，只提醒、每个都等你自己点")
                + (Prefs.isAdJump(app) ? "；跳转键也替你按" : "；跳转键不按"));

        statusAccounts(app);
        statusNovel(app);
        Log.i(TAG, "===== 状态完 =====");
    }

    private void statusAccounts(Context app) {
        AppDatabase db = Db.get(app);
        String ymd = Texts.todayYmd();
        List<Account> accounts = db.accountDao().loadAll();
        int enabled = 0;
        int signed = 0;
        int vouchers = 0;
        for (Account a : accounts) if (a.enabled) enabled++;
        Log.i(TAG, "账号 " + accounts.size() + " 个（启用 " + enabled + "）：");
        for (Account a : accounts) {
            CheckInLog today = db.checkInDao().find(a.id, ymd);
            boolean ok = today != null && today.isSuccess();
            if (ok && a.enabled) signed++;
            if (a.enabled && a.lastKnownVouchers > 0) vouchers += a.lastKnownVouchers;
            Log.i(TAG, "  id=" + a.id + (a.enabled ? " [启用] " : " [停用] ") + a.displayName()
                    + " " + a.balanceText()
                    + "｜今天：" + (today == null ? "还没跑" : today.status)
                    + "，广告已看 " + (today == null ? 0 : today.adsWatched)
                    + (today != null && today.adAvailable ? "（还有没领的）" : ""));
        }
        Log.i(TAG, "  今天已签到 " + signed + "/" + enabled + " 个号；"
                + "启用账号手上一共约 " + vouchers + " 代券（上次读到的数）");
    }

    private void statusNovel(Context app) {
        SubscriptionDao subs = Db.get(app).subscriptionDao();
        Novel novel = SubscribeRun.resolveTarget(subs, null);
        if (novel == null) {
            Log.w(TAG, "集中订阅目标：没有（登记了 " + subs.loadNovels().size()
                    + " 本又没标目标，订阅这一步不会跑）");
            return;
        }
        List<Chapter> chapters = subs.loadChapters(novel.id);
        Chapter next = subs.findNextUnownedChapterFrom(novel.id, novel.startFrom());
        int paid = 0;
        for (Account a : Db.get(app).accountDao().loadAll()) {
            paid += subs.countPaidPurchases(a.id, novel.id);
        }
        Log.i(TAG, "集中订阅目标：《" + novel.title + "》，登记 " + chapters.size()
                + " 章，从第" + novel.startFrom() + "章起买；"
                + "8 个号合起来已付费订阅 " + paid + " 章");
        Log.i(TAG, "  下一章（所有号合起来还没买过的最小章）＝ "
                + (next == null ? "没有了（登记的章都有号拥有了）"
                : "第" + next.chapterNo + "章「" + next.title + "」"));
    }
}
