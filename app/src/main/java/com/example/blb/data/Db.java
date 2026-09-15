package com.example.blb.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 数据库单例 + 一个单线程 IO 执行器。
 * 用单线程而不是线程池：本 App 的写入全部来自自动化队列和界面操作，
 * 串行化最省心，也不会出现并发写把账本写乱的情况。
 */
public final class Db {

    private static final String NAME = "blb.db";

    private static volatile AppDatabase instance;
    private static volatile Thread ioThread;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blb-io");
        ioThread = t;
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

    /**
     * 真机已有账号和账本，只补目录时间与核对证据，不能重建旧表或替用户推断扫过、核过。
     * DEFAULT 同时写在实体上，免得覆盖安装与全新安装生成两种表，Room 打开时拒绝旧库。
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE novel ADD COLUMN catalog_scanned_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE novel ADD COLUMN catalog_chapter_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS account_novel_audit ("
                    + "account_id INTEGER NOT NULL, "
                    + "novel_id INTEGER NOT NULL, "
                    + "aggregate_at INTEGER NOT NULL DEFAULT 0, "
                    + "aggregate_chapters INTEGER NOT NULL DEFAULT -1, "
                    + "detail_at INTEGER NOT NULL DEFAULT 0, "
                    + "detail_chapters INTEGER NOT NULL DEFAULT -1, "
                    + "ledger_marker TEXT, "
                    + "PRIMARY KEY(account_id, novel_id))");
            db.execSQL("CREATE TABLE IF NOT EXISTS ledger_audit ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "at INTEGER NOT NULL, "
                    + "account_id INTEGER NOT NULL, "
                    + "novel_id INTEGER NOT NULL, "
                    + "kind TEXT NOT NULL, "
                    + "chapter_id INTEGER NOT NULL DEFAULT 0, "
                    + "chapter_no INTEGER NOT NULL DEFAULT 0, "
                    + "title TEXT, "
                    + "detail TEXT)");
        }
    };

    /**
     * 2026-09-14 用户删除整条奖励视频链路；只保留真实签到结果，旧历史行和账号关联必须原样搬回。
     * SQLite 旧版本不能直接删列，所以在 Room 的升级事务内重建；任一语句失败由事务回滚，
     * 不使用破坏性迁移兜底。旧版本迁移仍保留历史列定义，才能让更早的安装沿同一条链升级。
     * 同次番外漏扫报告要求按卷消歧；旧章的卷名无证据，新增列保留 NULL，不能猜一个卷名。
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE check_in_log_v7 ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "account_id INTEGER NOT NULL, "
                    + "date_ymd TEXT, "
                    + "status TEXT, "
                    + "message TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "FOREIGN KEY(account_id) REFERENCES account(id) "
                    + "ON UPDATE NO ACTION ON DELETE CASCADE)");
            db.execSQL("INSERT INTO check_in_log_v7 (id, account_id, date_ymd, status, message, created_at) "
                    + "SELECT id, account_id, date_ymd, status, message, created_at FROM check_in_log");
            db.execSQL("DROP TABLE check_in_log");
            db.execSQL("ALTER TABLE check_in_log_v7 RENAME TO check_in_log");
            db.execSQL("CREATE UNIQUE INDEX index_check_in_log_account_id_date_ymd "
                    + "ON check_in_log (account_id, date_ymd)");
            db.execSQL("ALTER TABLE chapter ADD COLUMN volume_title TEXT");
        }
    };

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (Db.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(), AppDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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

    /** 购买事实不能因取消而排在队列释放之后才落库；等待短事务结束再把控制权交还自动化。 */
    public static <T> T call(Callable<T> task) {
        if (Thread.currentThread() == ioThread) {
            try {
                return task.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
        FutureTask<T> pending = new FutureTask<>(task);
        IO.execute(pending);
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return pending.get();
                } catch (InterruptedException failure) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
