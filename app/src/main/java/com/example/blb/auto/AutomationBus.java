package com.example.blb.auto;

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

    private static final int MAX_LOG_LINES = 300;

    private static final MutableLiveData<Boolean> RUNNING = new MutableLiveData<>(false);
    private static final MutableLiveData<String> STATUS = new MutableLiveData<>("空闲");
    private static final MutableLiveData<String> PAUSE_REASON = new MutableLiveData<>(null);
    private static final MutableLiveData<List<String>> LOG = new MutableLiveData<>(new ArrayList<>());

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final BlockingQueue<StepRunner.Decision> DECISIONS = new ArrayBlockingQueue<>(1);

    private static volatile boolean cancelled;

    private AutomationBus() {
    }

    public static LiveData<Boolean> running() {
        return RUNNING;
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
        return Boolean.TRUE.equals(RUNNING.getValue());
    }

    /** 由任务驱动方（前台 Service 或 WorkManager）调用。 */
    public static void setRunning(boolean value) {
        RUNNING.postValue(value);
        if (value) {
            cancelled = false;
            DECISIONS.clear();
        } else {
            PAUSE_REASON.postValue(null);
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
        cancelled = true;
        // 正卡在等人工时，取消也要能立刻把它唤醒。
        DECISIONS.offer(StepRunner.Decision.ABORT);
    }

    public static boolean isCancelled() {
        return cancelled;
    }

    // ---------- 暂停等人工 ----------

    /** 由任务线程调用，阻塞直到用户做出选择；等太久（30 分钟）就当作中止。 */
    static StepRunner.Decision awaitDecision(String reason) {
        PAUSE_REASON.postValue(reason);
        DECISIONS.clear();
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
        if (decision == StepRunner.Decision.ABORT) cancelled = true;
        DECISIONS.offer(decision);
    }
}
