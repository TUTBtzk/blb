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
import com.example.blb.data.SubscriptionDao;
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
 * </pre>
 */
public class ControlReceiver extends BroadcastReceiver {

    private static final String TAG = "BlbControl";

    private static final String ACTION_STATUS = "com.example.blb.debug.STATUS";
    private static final String ACTION_SCHEDULE = "com.example.blb.debug.SCHEDULE";
    private static final String ACTION_DAILY_NOW = "com.example.blb.debug.DAILY_NOW";
    private static final String ACTION_CHECKIN_NOW = "com.example.blb.debug.CHECKIN_NOW";

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
                else Log.w(TAG, "不认识的动作：" + action);
            } catch (Throwable t) {
                Log.e(TAG, "总控失败", t);
            } finally {
                pending.finish();
            }
        }, "blb-control").start();
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
