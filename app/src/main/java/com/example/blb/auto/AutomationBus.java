package com.example.blb.auto;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 自动化任务与界面之间的单一通道：状态、日志、以及「暂停等人工」时的决定。
 *
 * <p>做成静态的原因是任务由前台 Service 或 WorkManager 驱动，界面随时可能被销毁重建，
 * 用静态 LiveData 最不容易出现「跑着的任务找不到界面」这种状态错位。
 */
public final class AutomationBus {

    /**
     * 日志页最多留多少行。
     *
     * <p>原来是 300 —— 装不下一整趟：8 个号每号约 50 行（登号、余额、两层对账逐章打印、
     * 目录扫描、逐章尝试），2026-09-03 那趟跑完，前两个号的日志已经被挤掉了，而第83章
     * 恰恰是在那两个号身上没走通的，于是「为什么跳过了83章」在机器上查不到答案。
     *
     * <p>没有开得更大是因为日志页是一个 TextView 装全文（{@code TextUtils.join("\n", …)}），
     * 行数直接换成 measure 的开销。800 行装得下一整趟还有一倍余量。
     */
    private static final int MAX_LOG_LINES = 800;

    private static final MutableLiveData<Boolean> RUNNING = new MutableLiveData<>(false);
    private static final MutableLiveData<Boolean> BUSY = new MutableLiveData<>(false);
    private static final MutableLiveData<String> STATUS = new MutableLiveData<>("空闲");
    private static final MutableLiveData<String> PAUSE_REASON = new MutableLiveData<>(null);
    private static final MutableLiveData<List<String>> LOG = new MutableLiveData<>(new ArrayList<>());

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final BlockingQueue<StepRunner.Decision> DECISIONS = new ArrayBlockingQueue<>(1);

    private static final RunGate GATE = new RunGate();
    private static Runnable cancelCleanup;

    private AutomationBus() {
    }

    public static LiveData<Boolean> running() {
        return RUNNING;
    }

    public static LiveData<Boolean> busy() {
        return BUSY;
    }

    public static boolean isBusy() {
        return GATE.isBusy();
    }

    public static LiveData<String> status() {
        return STATUS;
    }

    /** 非 null 表示任务正卡在等人工处理。 */
    public static LiveData<String> pauseReason() {
        return PAUSE_REASON;
    }

    public static LiveData<List<String>> log() {
        return LOG;
    }

    public static boolean isRunning() {
        return GATE.isRunning();
    }

    /** Service 和 Worker 必须先取得同一把锁，再启动任何队列或清空日志。 */
    public static boolean tryStartRun(Object owner, Runnable onCancel) {
        synchronized (GATE) {
            if (!GATE.tryStart(owner)) return false;
            cancelCleanup = onCancel;
            DECISIONS.clear();
            PAUSE_REASON.postValue(null);
            RUNNING.postValue(true);
            BUSY.postValue(true);
            return true;
        }
    }

    /** 只有取得锁的任务能结束它，拒绝旧任务或未启动成功的任务清掉别人的状态。 */
    public static void finishRun(Object owner) {
        synchronized (GATE) {
            if (!GATE.isRunning() || !GATE.finish(owner)) return;
            cancelCleanup = null;
            PAUSE_REASON.postValue(null);
            RUNNING.postValue(false);
            BUSY.postValue(false);
        }
    }

    /** 编辑与自动化互斥，但编辑不会显示为可停止的自动化任务。 */
    public static boolean tryStartEdit(Object owner) {
        synchronized (GATE) {
            if (!GATE.tryStartEdit(owner)) return false;
            BUSY.postValue(true);
            return true;
        }
    }

    public static void finishEdit(Object owner) {
        synchronized (GATE) {
            if (GATE.isRunning() || !GATE.finish(owner)) return;
            BUSY.postValue(false);
        }
    }

    public static boolean ownsRun(Object owner) {
        synchronized (GATE) {
            return GATE.isRunning() && GATE.owns(owner);
        }
    }

    public static void setStatus(String text) {
        STATUS.postValue(text);
    }

    public static void append(String line) {
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LOG_LINES) LINES.removeFirst();
            LOG.postValue(new ArrayList<>(LINES));
        }
    }

    public static void clearLog() {
        synchronized (LINES) {
            LINES.clear();
            LOG.postValue(new ArrayList<>());
        }
    }

    // ---------- 取消 ----------

    public static void cancel() {
        synchronized (GATE) {
            if (!GATE.isRunning()) return;
            GATE.cancel();
            // 即使之前排了一次“继续”，停止也必须唤醒等待并覆盖旧决定。
            DECISIONS.clear();
            DECISIONS.offer(StepRunner.Decision.ABORT);
            if (cancelCleanup != null) {
                try {
                    cancelCleanup.run();
                } catch (RuntimeException e) {
                    Log.w("BlbAuto", "停止任务时清理返回闹钟失败", e);
                }
            }
        }
    }

    public static boolean isCancelled() {
        return GATE.isCancelled();
    }

    public static void cancelRun(Object owner) {
        synchronized (GATE) {
            if (GATE.isRunning() && GATE.owns(owner)) cancel();
        }
    }

    // ---------- 暂停等人工 ----------

    /** 由任务线程调用，阻塞直到用户做出选择；等太久（30 分钟）就当作中止。 */
    static StepRunner.Decision awaitDecision(String reason) {
        synchronized (GATE) {
            if (GATE.isCancelled()) return StepRunner.Decision.ABORT;
            DECISIONS.clear();
            PAUSE_REASON.postValue(reason);
        }
        try {
            StepRunner.Decision d = DECISIONS.poll(30, TimeUnit.MINUTES);
            return d == null ? StepRunner.Decision.ABORT : d;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepRunner.Decision.ABORT;
        } finally {
            PAUSE_REASON.postValue(null);
        }
    }

    /** 由通知按钮或界面调用。 */
    public static void submitDecision(@Nullable StepRunner.Decision decision) {
        if (decision == null) return;
        if (decision == StepRunner.Decision.ABORT) {
            cancel();
            return;
        }
        synchronized (GATE) {
            if (!GATE.isCancelled()) DECISIONS.offer(decision);
        }
    }
}
