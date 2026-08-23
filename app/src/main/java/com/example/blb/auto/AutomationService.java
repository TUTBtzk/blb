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
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.example.blb.MainActivity;
import com.example.blb.R;

/**
 * 拥有任务队列的前台 Service。它只负责生命周期、通知和线程，
 * 具体流程在 {@link CheckInQueue} 和 {@link SubscribeQueue} 里，这样 WorkManager 的每日任务
 * 能复用同一套逻辑。
 */
public class AutomationService extends Service implements StepRunner.Host {

    public static final String ACTION_RUN_CHECKIN = "com.example.blb.action.RUN_CHECKIN";
    public static final String ACTION_RUN_SUBSCRIBE = "com.example.blb.action.RUN_SUBSCRIBE";
    public static final String ACTION_RUN_DAILY = "com.example.blb.action.RUN_DAILY";
    public static final String ACTION_CONTINUE = "com.example.blb.action.CONTINUE";
    public static final String ACTION_SKIP = "com.example.blb.action.SKIP";
    public static final String ACTION_ABORT = "com.example.blb.action.ABORT";

    public static final String CHANNEL_ID = "blb_auto";
    private static final int NOTIF_ID = 1001;
    private static final String TAG = "BlbAuto";

    /** 队列类型。通知标题和收尾文案都跟着它走，别让订阅跑完弹「签到任务结束」。 */
    private enum Mode {
        CHECK_IN("签到"), SUBSCRIBE("集中订阅"), DAILY("每日流程");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private Thread worker;
    private String lastLine = "准备中…";
    private Mode mode = Mode.CHECK_IN;

    public static void startCheckIn(Context context) {
        Intent intent = new Intent(context, AutomationService.class).setAction(ACTION_RUN_CHECKIN);
        context.startService(intent);
    }

    public static void startSubscribe(Context context) {
        Intent intent = new Intent(context, AutomationService.class).setAction(ACTION_RUN_SUBSCRIBE);
        context.startService(intent);
    }

    /** 签到 → 广告 → 订阅，一个号一趟走完。 */
    public static void startDaily(Context context) {
        Intent intent = new Intent(context, AutomationService.class).setAction(ACTION_RUN_DAILY);
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

        switch (action) {
            case ACTION_RUN_CHECKIN:
                startQueue(Mode.CHECK_IN);
                break;
            case ACTION_RUN_SUBSCRIBE:
                startQueue(Mode.SUBSCRIBE);
                break;
            case ACTION_RUN_DAILY:
                startQueue(Mode.DAILY);
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

    private void startQueue(Mode next) {
        if (worker != null && worker.isAlive()) {
            AutomationBus.append("已经有任务在跑了");
            return;
        }
        if (!BlbAccessibilityService.isReady()) {
            AutomationBus.append("无障碍服务没开启，先去系统设置里打开「blb自动签到」");
            AutomationBus.setStatus("无障碍服务未开启");
            stopSelf();
            return;
        }

        mode = next;
        startForegroundSafely(buildNotification(next.label + "队列启动中…", false));
        AutomationBus.clearLog();
        AutomationBus.setRunning(true);
        AutomationBus.setStatus("运行中");

        worker = new Thread(() -> runQueue(next), "blb-automation");
        worker.start();
    }

    private void runQueue(Mode current) {
        String text = current.label + "任务异常终止";
        try {
            switch (current) {
                case SUBSCRIBE:
                    text = SubscribeQueue.describe(SubscribeQueue.run(this, this));
                    break;
                case DAILY:
                    text = DailyQueue.describe(DailyQueue.run(this, this));
                    break;
                default:
                    text = describe(CheckInQueue.run(this, this));
                    break;
            }
        } catch (Throwable t) {
            Log.e(TAG, current.label + "队列崩了", t);
            AutomationBus.append("队列异常终止：" + t);
        } finally {
            AutomationBus.setRunning(false);
            AutomationBus.setStatus(text);
            notifyFinished(current, text);
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
            stopSelf();
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
