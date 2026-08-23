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

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (Db.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(), AppDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2)
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
