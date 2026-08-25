package com.example.blb.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据库单例 + 一个单线程 IO 执行器。
 * 用单线程而不是线程池：本 App 的写入全部来自自动化队列和界面操作，
 * 串行化最省心，也不会出现并发写把账本写乱的情况。
 */
public final class Db {

    private static final String NAME = "blb.db";

    private static volatile AppDatabase instance;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blb-io");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private Db() {
    }

    /**
     * v1 → v2：加「登录方式 / 代券余额 / 起始章 / 广告计数」四类字段。
     *
     * <p>手机上已经有账号和账本了，所以只能加列，绝不能销毁重建。
     * NOT NULL 的列必须带 DEFAULT，否则 SQLite 拒绝 ALTER TABLE。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE account ADD COLUMN login_kind TEXT NOT NULL DEFAULT 'PASSWORD'");
            db.execSQL("ALTER TABLE account ADD COLUMN last_known_vouchers INTEGER NOT NULL DEFAULT -1");
            db.execSQL("ALTER TABLE novel ADD COLUMN start_chapter_no INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE check_in_log ADD COLUMN ads_watched INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE check_in_log ADD COLUMN ads_remaining INTEGER NOT NULL DEFAULT -1");
        }
    };

    /**
     * v2 → v3：把「从来没读到过余额」的火券从 0 改成 -1（＝不知道）。
     *
     * <p>手机上那 8 个号里有 7 个的 last_known_coupons 是建表时的默认 0，界面于是画出
     * 「火券 0」——一个编出来的数字。判据是代券：余额永远是两种券一起读出来的，所以
     * 「代券还是 -1」就意味着这一行的火券也从来没有被真的读到过。
     *
     * <p>只改数据不改表结构，所以没有 ALTER。
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("UPDATE account SET last_known_coupons = -1 "
                    + "WHERE last_known_vouchers < 0 AND last_known_coupons = 0");
        }
    };

    /**
     * v3 → v4：账本加一列「实付代券」。
     *
     * <p>以前只有 {@code cost_coupons}（火券）一列。新的订阅流程只允许「实付火券 == 0」的章
     * 买下去，真正花掉的全是代券 —— 全记在 cost_coupons 里会让「今天花了多少火券」这本账
     * 完全失真，而每日花费上限就是照着它算的。
     *
     * <p>手机上是真账本，所以只加列、绝不销毁重建。已有的行补 0：那些是旧流程按火券记的账，
     * 代券花费无从得知，编一个数字比留 0 更糟。
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE purchase ADD COLUMN cost_vouchers INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v4 → v5：把账本里遗留的干跑记录（{@code source='DRY_RUN'}）全部删掉。
     *
     * <p>干跑功能整套删了，所有查询里那句「排除 DRY_RUN」也跟着删了。要是不先把这些行清掉，
     * 它们下一秒就会被当成<b>真的有号买过这一章</b> —— 于是那些章会被永久跳过，
     * 一本书直接漏订，正好撞在「不多订、不漏订，8 个号拼出完整一本」这条硬约束上。
     *
     * <p>删它们不丢任何真实数据：干跑记的是「判定买得起」，花费两列都是 0，一分券都没花过。
     * 真实购买（AUTO）、手动补录（MANUAL）、按「已下载」回填（OWNED）一条都不动。
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DELETE FROM purchase WHERE source = 'DRY_RUN'");
        }
    };

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (Db.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(), AppDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                    MIGRATION_4_5)
                            .build();
                }
            }
        }
        return instance;
    }

    /** 把一段数据库操作丢到 IO 线程。 */
    public static void io(Runnable task) {
        IO.execute(task);
    }
}
