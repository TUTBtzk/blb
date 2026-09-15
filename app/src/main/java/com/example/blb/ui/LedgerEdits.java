package com.example.blb.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.example.blb.auto.AutomationBus;
import com.example.blb.data.Db;
import java.util.concurrent.Callable;

/** 运行中的队列会持有账号/章节快照，不能在它写回购买结果前删除或覆盖这些记录。 */
public final class LedgerEdits {

    private LedgerEdits() {
    }

    static boolean requireIdle(Context context) {
        if (!AutomationBus.isBusy()) return true;
        showBusy(context);
        return false;
    }

    static boolean tryStart(Context context, Object owner) {
        if (AutomationBus.tryStartEdit(owner)) return true;
        showBusy(context);
        return false;
    }

    private static void showBusy(Context context) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
                "有任务正在运行或账本正在更新，请等结束后再修改或删除", Toast.LENGTH_SHORT).show());
    }

    static void submit(Context context, Runnable edit) {
        submit(context, edit, null);
    }

    /** 数据与核对证据必须一起提交；成功提示也要等提交结束，不能先报成功再回滚。 */
    static void submit(Context context, Runnable edit, Runnable afterCommit) {
        Context app = context.getApplicationContext();
        if (!requireIdle(app)) return;
        Db.io(() -> {
            // 与队列启动共用同一占用，关掉检查完成后队列才启动的竞态窗口。
            Object owner = new Object();
            if (!tryStart(app, owner)) return;
            try {
                Db.get(app).runInTransaction(edit);
                if (afterCommit != null) new Handler(Looper.getMainLooper()).post(afterCommit);
            } catch (RuntimeException failure) {
                // 拒绝重复归属或无证据删账是给人的结论，不能只让后台执行器悄悄吃掉异常。
                String reason = failure.getMessage();
                String message = "修改失败，未保存：" + (reason == null || reason.trim().isEmpty()
                        ? failure.getClass().getSimpleName() : reason);
                AutomationBus.append(message);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, message, Toast.LENGTH_LONG).show());
            } finally {
                AutomationBus.finishEdit(owner);
            }
        });
    }

    /** 队列不能先释放运行锁再伪装成人工编辑，否则下一账号可能读到修了一半的账本。 */
    public static <T> T duringRun(Object owner, Callable<T> edit) {
        if (!AutomationBus.ownsRun(owner)) {
            throw new IllegalStateException("当前任务没有账本写入权");
        }
        return Db.call(() -> {
            if (!AutomationBus.ownsRun(owner)) {
                throw new IllegalStateException("任务已结束，拒绝延迟写入账本");
            }
            return edit.call();
        });
    }
}
