package com.example.blb.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.example.blb.MainActivity;
import com.example.blb.R;
import com.example.blb.ui.DoneDialogActivity;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

/**
 * 拥有任务队列的前台 Service。它只负责生命周期、通知和线程，
 * 具体流程在 {@link CheckInQueue} 和 {@link SubscribeQueue} 里，这样 WorkManager 的每日任务
 * 能复用同一套逻辑。
 */
public class AutomationService extends Service implements StepRunner.Host {

    public static final String ACTION_RUN_CHECKIN = "com.example.blb.action.RUN_CHECKIN";
    public static final String ACTION_RUN_SUBSCRIBE = "com.example.blb.action.RUN_SUBSCRIBE";
    public static final String ACTION_RUN_DAILY = "com.example.blb.action.RUN_DAILY";
    /** 只切号，什么都不买，见 {@link SwitchAccountQueue}。 */
    public static final String ACTION_SWITCH_ACCOUNT = "com.example.blb.action.SWITCH_ACCOUNT";
    public static final String ACTION_CONTINUE = "com.example.blb.action.CONTINUE";
    public static final String ACTION_SKIP = "com.example.blb.action.SKIP";
    public static final String ACTION_ABORT = "com.example.blb.action.ABORT";
    /**
     * 「这一趟是用户自己在界面上按出来的」。没有这个 extra 的启动只可能来自系统：
     * 进程被杀之后 ActivityManager 会把原来那条 startService 请求重发一遍（实测
     * {@code Scheduling restart of crashed service … AutomationService in 10000ms for
     * start-requested}），于是队列会自己又跑一趟。见 {@link #startQueue}。
     */
    private static final String EXTRA_FROM_UI = "com.example.blb.extra.FROM_UI";
    /**
     * 要切到哪个账号（{@link #ACTION_SWITCH_ACCOUNT} 用）。放在 intent 里而不是只放静态字段：
     * 进程被杀之后系统重发的那趟 intent 会原样带着它回来，不然重发的一趟不知道该登谁。
     */
    private static final String EXTRA_ACCOUNT_ID = "com.example.blb.extra.ACCOUNT_ID";

    public static final String CHANNEL_ID = "blb_auto";
    private static final int NOTIF_ID = 1001;
    private static final String TAG = "BlbAuto";
    /** 屏幕锁的保险丝：一趟 8 个号最多也就十几分钟，两小时是防「忘了放」用的。 */
    private static final long SCREEN_LOCK_LIMIT_MS = 2 * 60 * 60 * 1000L;
    /**
     * 开跑前等无障碍服务连上来的时间。系统把被杀掉的无障碍服务排在 30 s 后重启
     * （{@code … BlbAccessibilityService in 30000ms for connection}），而队列自己 10 s 就回来了，
     * 所以这里必须等得比 30 s 长，否则每次被杀都换来一句「无障碍服务未开启」。
     */
    private static final long A11Y_WAIT_MS = 45_000;
    /** 一天里最多自动接着跑几趟，见 {@link Prefs#autoResumes}。 */
    private static final int MAX_AUTO_RESUMES = 3;

    /** 队列类型。通知标题和收尾文案都跟着它走，别让订阅跑完弹「签到任务结束」。 */
    private enum Mode {
        CHECK_IN("签到"), SUBSCRIBE("集中订阅"), DAILY("每日流程"), SWITCH("切换账号");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private Thread worker;
    private String lastLine = "准备中…";
    private Mode mode = Mode.CHECK_IN;
    /** 这一趟要切到哪个号（只有 {@link Mode#SWITCH} 用得上）。 */
    private long switchAccountId;
    /** 整趟任务期间点着屏幕的那把锁，见 {@link #acquireScreenLock}。 */
    private PowerManager.WakeLock screenLock;

    public static void startCheckIn(Context context) {
        Intent intent = new Intent(context, AutomationService.class).setAction(ACTION_RUN_CHECKIN)
                .putExtra(EXTRA_FROM_UI, true);
        context.startService(intent);
    }

    public static void startSubscribe(Context context) {
        Intent intent = new Intent(context, AutomationService.class).setAction(ACTION_RUN_SUBSCRIBE)
                .putExtra(EXTRA_FROM_UI, true);
        context.startService(intent);
    }

    /** 签到 → 广告 → 订阅，一个号一趟走完。 */
    public static void startDaily(Context context) {
        Intent intent = new Intent(context, AutomationService.class).setAction(ACTION_RUN_DAILY)
                .putExtra(EXTRA_FROM_UI, true);
        context.startService(intent);
    }

    /**
     * 只切号：退出现在登着的号，登入 {@code accountId} 那个号。
     *
     * <p>章节列表点一下「已订阅：某号」的行就走这条路 —— 想看那一章就得登那个号，而这十几步
     * 屏幕操作使用者做不了。已经登着它的时候这一趟什么都不会做。
     */
    public static void startSwitchAccount(Context context, long accountId) {
        Intent intent = new Intent(context, AutomationService.class)
                .setAction(ACTION_SWITCH_ACCOUNT)
                .putExtra(EXTRA_FROM_UI, true)
                .putExtra(EXTRA_ACCOUNT_ID, accountId);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "签到任务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示签到队列进度，以及需要你手动处理时的提示");
        nm.createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return START_NOT_STICKY;
        boolean fromUi = intent.getBooleanExtra(EXTRA_FROM_UI, false);

        switch (action) {
            case ACTION_RUN_CHECKIN:
                startQueue(Mode.CHECK_IN, fromUi);
                break;
            case ACTION_RUN_SUBSCRIBE:
                startQueue(Mode.SUBSCRIBE, fromUi);
                break;
            case ACTION_RUN_DAILY:
                startQueue(Mode.DAILY, fromUi);
                break;
            case ACTION_SWITCH_ACCOUNT:
                switchAccountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, 0);
                startQueue(Mode.SWITCH, fromUi);
                break;
            case ACTION_CONTINUE:
                AutomationBus.submitDecision(StepRunner.Decision.CONTINUE);
                break;
            case ACTION_SKIP:
                AutomationBus.submitDecision(StepRunner.Decision.SKIP);
                break;
            case ACTION_ABORT:
                AutomationBus.cancel();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void startQueue(Mode next, boolean fromUi) {
        if (worker != null && worker.isAlive()) {
            AutomationBus.append("已经有任务在跑了");
            return;
        }
        String ymd = Texts.todayYmd();
        if (fromUi) Prefs.clearAutoResumes(this, ymd);
        else if (!allowAutoResume(ymd)) return;

        mode = next;
        // 上一趟那个结论弹窗还开着的话先收掉：它不会自己消失（他可能过一会儿才看），
        // 但绝不能挡着这一趟要操作的界面。
        DoneDialogActivity.dismissOpen();
        startForegroundSafely(buildNotification(next.label + "队列启动中…", false));
        // 系统重发的那趟不清日志：上一趟被杀之前那几行是唯一的现场记录。
        if (fromUi) AutomationBus.clearLog();
        AutomationBus.setRunning(true);
        AutomationBus.setStatus("运行中");

        worker = new Thread(() -> runQueue(next), "blb-automation");
        worker.start();
    }

    /**
     * 这趟不是用户按出来的，是系统把原来那条启动请求重发了 —— 要不要接着跑。
     *
     * <p>接着跑本身是必要的：用户按不动屏幕，被杀掉之后没人能替他重按一次。但得有上限，
     * 万一某个号每次都卡在同一步，无限重启就变成反复退登重登，那是最招验证码的动作。
     */
    private boolean allowAutoResume(String ymd) {
        int done = Prefs.autoResumes(this, ymd);
        if (done >= MAX_AUTO_RESUMES) {
            AutomationBus.append("今天进程已经被系统杀掉 " + done
                    + " 次了，这次不再自动接着跑：再重来只会把同一个号反复重登（最招验证码）。"
                    + "等你按一下「跑今天的流程」再继续。");
            AutomationBus.setStatus("被系统杀了 " + done + " 次，等你按一下再继续");
            stopSelf();
            return false;
        }
        Prefs.noteAutoResume(this, ymd);
        AutomationBus.append("上一趟被系统杀掉了（MIUI 的 SwipeUpClean），自动接着跑（第 "
                + (done + 1) + " 次）；今天已经签完、广告也没剩的号会直接跳过。");
        return true;
    }

    private void runQueue(Mode current) {
        String text = current.label + "任务异常终止";
        // 弹窗要的是「结论」，通知和状态栏要的是「全文」，所以两份并行攒着：
        // text 那一串包含对账细节（22 章已下载但无归属…），一屏放不下，不能拿去弹。
        RunReport report = null;
        acquireScreenLock(current);
        try {
            if (!awaitAccessibility()) {
                text = "无障碍服务没连上，先去系统设置里打开「blb自动签到」";
                report = RunReport.failed(current.label, text);
                return;
            }
            switch (current) {
                case SUBSCRIBE: {
                    SubscribeQueue.Summary s = SubscribeQueue.run(this, this);
                    text = SubscribeQueue.describe(s);
                    report = RunReport.ofSubscribe(s);
                    break;
                }
                case DAILY: {
                    // 每日整套流程是「签到 → 广告 → 订阅」一条龙：签到和广告挣回来的代券
                    // 当场就拿去订阅，一个号买到代券不够再换下一个号。
                    DailyQueue.Summary s = DailyQueue.run(this, this);
                    text = DailyQueue.describe(s);
                    report = RunReport.ofDaily(s);
                    break;
                }
                case SWITCH:
                    text = SwitchAccountQueue.run(this, this, switchAccountId);
                    report = RunReport.ofSwitch(text);
                    break;
                default: {
                    CheckInQueue.Summary s = CheckInQueue.run(this, this);
                    text = describe(s);
                    report = RunReport.ofCheckIn(s);
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, current.label + "队列崩了", t);
            AutomationBus.append("队列异常终止：" + t);
            report = RunReport.failed(current.label, "队列异常终止：" + t);
        } finally {
            releaseScreenLock();
            AutomationBus.setRunning(false);
            AutomationBus.setStatus(text);
            notifyFinished(current, text);
            // 结论盖到屏幕上。使用者手指动不了：通知栏他拉不开，这个频道又是 IMPORTANCE_LOW
            // （进度每几秒刷一行，不能每次都弹横幅），只发通知等于这条结论他永远读不到。
            DoneDialogActivity.show(this,
                    report != null ? report : RunReport.failed(current.label, text));
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    /**
     * 等无障碍服务连上来再开跑。
     *
     * <p>不能像以前那样「没连上就立刻中止」：进程被 MIUI 杀掉之后，系统把队列排在 10 s 后重启、
     * 把无障碍服务排在 30 s 后重启，队列一定先醒 —— 那一趟 [2/8] 就是这么报「无障碍服务未开启」
     * 把整队掐死的，其实再等十几秒它自己就回来了。
     */
    private boolean awaitAccessibility() {
        if (BlbAccessibilityService.isReady()) return true;
        AutomationBus.append("无障碍服务还没连上，等它回来（最多 " + (A11Y_WAIT_MS / 1000) + " 秒）…");
        if (BlbAccessibilityService.awaitReady(A11Y_WAIT_MS)) {
            AutomationBus.append("无障碍服务连上了，开始跑");
            return true;
        }
        AutomationBus.append("等了 " + (A11Y_WAIT_MS / 1000)
                + " 秒无障碍服务还是没连上，这趟不跑了（去系统设置里把「blb自动签到」重新打开）");
        return false;
    }

    /**
     * 整趟任务期间把屏幕点着。
     *
     * <p>为什么非要这个：无障碍的点击和手势只对<b>亮着的屏幕</b>有效，而息屏之后顶上来的是
     * 系统的息屏/锁屏窗口（{@code com.android.systemui}），我们既读不到菠萝包的树、也拉不起它。
     * 2026-08-23 19:50 那一趟就是这么断的：跑到第 5 个号时屏幕已经自己睡了，后面 4 个号全部
     * 报「拉不起菠萝包（当前前台是 com.android.systemui）」。手指动不了的人没法每隔一会儿
     * 去戳一下屏幕，所以这件事必须由程序自己扛。
     *
     * <p>屏幕锁是 API 17 起标记 deprecated 的，但至今仍然有效，而且是普通 App 唯一能在没有
     * 前台 Activity 的情况下把屏幕点着的手段；换 FLAG_KEEP_SCREEN_ON 只在我们自己的界面在
     * 前台时有用，而这趟任务全程都在别人的界面上。{@code ACQUIRE_CAUSES_WAKEUP} 让定时那趟
     * 在息屏时也能把屏幕唤起来——不过<b>屏幕锁屏密码解不开</b>，那种情况仍然要人先解锁。
     */
    @SuppressWarnings("deprecation")
    private void acquireScreenLock(Mode current) {
        releaseScreenLock();
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "blb:" + current.name());
            screenLock.setReferenceCounted(false);
            // 上限只是保险丝：正常路径在 finally 里就放了。
            screenLock.acquire(SCREEN_LOCK_LIMIT_MS);
        } catch (Exception e) {
            Log.w(TAG, "点不亮屏幕（拿不到屏幕锁）", e);
            screenLock = null;
        }
    }

    private void releaseScreenLock() {
        PowerManager.WakeLock lock = screenLock;
        screenLock = null;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (Exception e) {
            Log.w(TAG, "放屏幕锁时出错", e);
        }
    }

    private static String describe(CheckInQueue.Summary s) {
        if (s == null) return "任务异常终止";
        StringBuilder sb = new StringBuilder();
        sb.append("成功 ").append(s.ok)
                .append("，已签 ").append(s.already)
                .append("，失败 ").append(s.failed);
        if (s.skipped > 0) sb.append("，跳过 ").append(s.skipped);
        if (!s.adPending.isEmpty()) {
            sb.append("；还有广告没领：").append(TextUtils.join("、", s.adPending));
        }
        if (s.aborted()) sb.append("（中止：").append(s.abortReason).append('）');
        return sb.toString();
    }

    // ---------- StepRunner.Host ----------

    @Override
    public boolean isCancelled() {
        return AutomationBus.isCancelled();
    }

    @Override
    public void log(String message) {
        lastLine = message;
        // 也进 logcat：跑队列的时候绝不能 uiautomator dump（会把我们的无障碍服务顶下去），
        // 所以 `adb logcat -s BlbAuto` 是真机验证时唯一能实时看到进度的窗口。
        // 密码永远不经过这里（只在内存里经 KeyStoreBox 传给输入框）。
        Log.i(TAG, message);
        AutomationBus.append(message);
        notifyManager().notify(NOTIF_ID, buildNotification(message, false));
    }

    @Override
    public StepRunner.Decision awaitUser(String reason) {
        AutomationBus.append("暂停：" + reason);
        notifyManager().notify(NOTIF_ID, buildNotification(reason, true));
        StepRunner.Decision decision = AutomationBus.awaitDecision(reason);
        AutomationBus.append("你选择了 " + decision);
        notifyManager().notify(NOTIF_ID, buildNotification(lastLine, false));
        return decision;
    }

    // ---------- 通知 ----------

    private NotificationManager notifyManager() {
        return getSystemService(NotificationManager.class);
    }

    private void startForegroundSafely(Notification notification) {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
        } catch (Exception e) {
            // 后台启动前台服务被限制时不能让整个流程崩掉，退化成普通服务继续跑。
            Log.w(TAG, "startForeground 被拒", e);
        }
    }

    private Notification buildNotification(String text, boolean paused) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(paused ? "需要你处理一下" : mode.label + "任务进行中")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(!paused)
                .setPriority(paused ? NotificationCompat.PRIORITY_HIGH
                        : NotificationCompat.PRIORITY_LOW)
                .setContentIntent(activityIntent());

        if (paused) {
            b.addAction(0, "继续", serviceIntent(ACTION_CONTINUE, 11));
            b.addAction(0, "跳过此账号", serviceIntent(ACTION_SKIP, 12));
        }
        b.addAction(0, "停止", serviceIntent(ACTION_ABORT, 13));
        return b.build();
    }

    private void notifyFinished(Mode current, String text) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(current.label + "任务结束")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(activityIntent())
                .build();
        notifyManager().notify(NOTIF_ID, n);
    }

    private PendingIntent activityIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, AutomationService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
