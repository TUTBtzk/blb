package com.example.blb.ui;

/**
 * 订阅页最后那一行：「今天有几个号在跑、每个号最多花多少」。
 *
 * <p>为什么放在最下面而不是塞进按钮：上限是设置页里的一个开关（{@code Prefs.dailySpendCap}，
 * 0＝不限），而它决定这一趟到底敢不敢放心跑。按钮上写的是「这次做什么」，
 * 这一行写的是「做起来最多花多少」，两件事分开才读得清楚。
 *
 * <p>单位是代券：现在的自动订阅只买「实付 0 火券」的章，火券不进这个上限。
 * 这个类不碰 Android API，能进普通单元测试。
 */
final class SpendSummary {

    private SpendSummary() {
    }

    /**
     * @param enabledAccounts 启用中的账号数
     * @param capPerAccount   每号每天的代券上限；0＝不限（设置页可改）
     */
    static String line(int enabledAccounts, int capPerAccount) {
        if (enabledAccounts <= 0) return "还没有启用账号 · 先在账号页启用，队列才有号可跑";
        if (capPerAccount <= 0) {
            return enabledAccounts + " 个启用账号 · 每号每天花费不限（可在设置页设上限）";
        }
        return enabledAccounts + " 个启用账号 · 每号每天最多花 " + capPerAccount + " 代券";
    }
}
