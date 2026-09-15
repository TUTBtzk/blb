package com.example.blb.auto;

/** 取消与延迟动作共用的闸门；持锁执行动作，取消返回后旧动作就不可能再开始。 */
final class ReturnWatchdogGuard {
    private long activeToken;
    private long invalidatedThrough;

    synchronized boolean activate(long token) {
        if (token <= invalidatedThrough || token <= 0) return false;
        activeToken = token;
        return true;
    }

    synchronized void cancel() {
        invalidatedThrough = Math.max(invalidatedThrough, activeToken);
        activeToken = 0;
    }

    synchronized boolean isCurrent(long token) {
        return token > 0 && token == activeToken;
    }

    synchronized boolean runIfCurrent(long token, Runnable action) {
        if (!isCurrent(token)) return false;
        action.run();
        return true;
    }
}
