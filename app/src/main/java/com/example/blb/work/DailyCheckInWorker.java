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
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.DailyQueue;
import com.example.blb.auto.StepRunner;

/**
 * 每日定时跑一遍 {@link DailyQueue}（签到 → 广告 → 订阅），跟手动点「跑今天的流程」是同一套代码。
 *
 * <p>不起前台服务：真正干活的是常驻的无障碍服务，Worker 只是个触发器。
 *
 * <p>与手动运行的唯一区别是遇到「需要人工」时的处理 —— 定时跑的时候人可能不在，所以这里
 * 直接跳过并推通知，绝不占着 Worker 干等（WorkManager 十分钟就会掐掉它）。
 * 广告这一步同理：定时跑一律不替你点（{@code attended=false}），因为广告是给你看的，
 * 你不在场时替你点开只是让广告主为没人看的曝光付钱。通知会告诉你哪个号还差几个，
 * 回 App 点「跑今天的流程」时再由脚本替你按键、你自己看。
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
        if (!BlbAccessibilityService.isReady()) {
            // 无障碍被系统或用户关掉了，重试也没用，等下一个周期。
            notify("每日流程没能开始", "无障碍服务没开启，请到设置页重新打开。");
            return Result.success();
        }
        if (AutomationBus.isRunning()) {
            return Result.success();
        }

        AutomationBus.clearLog();
        AutomationBus.setRunning(true);
        AutomationBus.setStatus("每日流程运行中");
        DailyQueue.Summary summary = null;
        try {
            summary = DailyQueue.run(getApplicationContext(), this, false);
        } catch (Throwable t) {
            Log.e(TAG, "每日流程失败", t);
            AutomationBus.append("每日流程异常：" + t);
        } finally {
            AutomationBus.setRunning(false);
            AutomationBus.setStatus(summary == null
                    ? "每日流程异常终止" : DailyQueue.describe(summary));
        }

        if (summary == null) {
            notify("每日流程异常", "任务中途出错，去 App 里看日志。");
            return Result.retry();
        }
        notify("每日流程完成", DailyQueue.describe(summary));
        // 全局性中止（拉不起菠萝包、选择器缺失）退避重试一次还有救；其余算完成。
        return summary.aborted() ? Result.retry() : Result.success();
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
