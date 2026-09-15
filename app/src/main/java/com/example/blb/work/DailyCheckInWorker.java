package com.example.blb.work;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.blb.MainActivity;
import com.example.blb.R;
import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.DailyQueue;
import com.example.blb.auto.ReturnWatchdog;
import com.example.blb.auto.ScreenAwake;
import com.example.blb.auto.StepRunner;
import com.example.blb.util.Prefs;

/**
 * 每日定时跑一遍 {@link DailyQueue}（逐号：切号 → 签到 → 订阅），与签到页的整套入口共用流程。
 *
 * <p>Worker 持有和手动任务相同的运行锁和亮屏资源，具体点击仍由无障碍服务完成。
 *
 * <p>与手动运行的唯一区别是遇到「需要人工」时的处理 —— 定时跑的时候人可能不在，所以这里
 * 直接跳过并推通知，绝不占着 Worker 干等（WorkManager 十分钟就会掐掉它）。
 */
public class DailyCheckInWorker extends Worker implements StepRunner.Host {

    private static final String TAG = "BlbAuto";
    private static final int NOTIF_ID = 1002;

    public DailyCheckInWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        if (!AutomationBus.tryStartRun(this, () -> ReturnWatchdog.disarm(app))) {
            // 手动运行或导入仍在使用账号/账本时稍后再试，不能把这一天当作已完成。
            return Result.retry();
        }
        try (ScreenAwake ignored = ScreenAwake.acquire(app, "SCHEDULED")) {
            AutomationBus.clearLog();
            AutomationBus.setStatus("每日流程准备中");
            if (isCancelled()) return completed("每日流程已停止", "已取消");
            AccessibilityAccess.State a11y = AccessibilityAccess.state(app);
            if (a11y == AccessibilityAccess.State.DISABLED) {
                AccessibilityAccess.RestoreResult restored =
                        AccessibilityAccess.restoreIfAuthorized(app);
                if (restored == AccessibilityAccess.RestoreResult.NOT_AUTHORIZED) {
                    return completed("每日流程没能开始",
                            "系统无障碍开关已关闭，设备尚未完成托管恢复授权。");
                }
                if (restored == AccessibilityAccess.RestoreResult.FAILED) {
                    return retryLater("自动补回无障碍服务失败，稍后再试。");
                }
            }
            long deadline = android.os.SystemClock.elapsedRealtime() + 45_000;
            while (!BlbAccessibilityService.isConnected()
                    && android.os.SystemClock.elapsedRealtime() < deadline && !isCancelled()) {
                BlbAccessibilityService.awaitConnected(500);
            }
            if (isCancelled()) return completed("每日流程已停止", "已取消");
            if (!BlbAccessibilityService.isConnected()) {
                return retryLater("无障碍系统开关已开启，服务仍在等待连接。");
            }
            AutomationBus.setStatus("每日流程运行中");
            DailyQueue.Summary summary = DailyQueue.run(app, this);
            if (isCancelled()) return completed("每日流程已停止", DailyQueue.describe(summary));
            if (summary.aborted()) return retryLater(DailyQueue.describe(summary));
            return completed("每日流程完成", DailyQueue.describe(summary));
        } catch (Exception e) {
            Log.e(TAG, "每日流程失败", e);
            AutomationBus.append("每日流程异常：" + e);
            return retryLater("任务中途出错，去 App 里看日志。");
        } finally {
            try {
                ReturnWatchdog.disarm(app);
            } finally {
                AutomationBus.finishRun(this);
            }
        }
    }

    private Result completed(String title, String text) {
        AutomationBus.setStatus(text);
        notify(title, text);
        // 明天继续对齐设置中的时刻，避免每天把本次耗时累积进运行时间。
        if (!isStopped() && Prefs.isDailyEnabled(getApplicationContext())) {
            DailyScheduler.apply(getApplicationContext());
        }
        return Result.success();
    }

    private Result retryLater(String text) {
        AutomationBus.setStatus(text);
        notify("每日流程稍后重试", text);
        return Result.retry();
    }

    @Override
    public void onStopped() {
        // 仅取消本 Worker 的任务，不干扰并发启动失败后正在运行的手动队列。
        AutomationBus.cancelRun(this);
    }

    // ---------- StepRunner.Host ----------

    @Override
    public boolean isCancelled() {
        return isStopped() || AutomationBus.isCancelled();
    }

    @Override
    public void log(String message) {
        AutomationBus.append(message);
    }

    @Override
    public StepRunner.Decision awaitUser(String reason) {
        notify("有一步需要你手动处理", reason + "\n定时任务里没法等你，这一步先跳过了，"
                + "回 App 点「跑今天的流程」可以接着做。");
        return StepRunner.Decision.SKIP;
    }

    // ---------- 通知 ----------

    private void notify(String title, String text) {
        Context ctx = getApplicationContext();
        AutomationService.ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification n = new NotificationCompat.Builder(ctx, AutomationService.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 2, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }
}
