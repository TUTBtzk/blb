package com.example.blb.auto;

/** 同一时间只有一个任务能操作目标应用；所有权与界面的异步通知分开保存。 */
final class RunGate {
    private Object owner;
    private boolean cancelled;
    private boolean editing;

    synchronized boolean tryStart(Object candidate) {
        return acquire(candidate, false);
    }

    synchronized boolean tryStartEdit(Object candidate) {
        return acquire(candidate, true);
    }

    private boolean acquire(Object candidate, boolean edit) {
        if (candidate == null) throw new IllegalArgumentException("owner must not be null");
        if (owner != null) return false;
        owner = candidate;
        editing = edit;
        cancelled = false;
        return true;
    }

    synchronized boolean finish(Object candidate) {
        if (!owns(candidate)) return false;
        owner = null;
        editing = false;
        return true;
    }

    synchronized boolean owns(Object candidate) {
        return owner != null && owner == candidate;
    }

    synchronized boolean isRunning() {
        return owner != null && !editing;
    }

    synchronized boolean isBusy() {
        return owner != null;
    }

    synchronized void cancel() {
        if (isRunning()) cancelled = true;
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }
}
