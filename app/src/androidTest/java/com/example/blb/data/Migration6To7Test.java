package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 2026-09-14 删除旧奖励统计并给无标号番外补卷名字段，不能把真实签到历史或账本丢掉。
 * 冻结 v6 Room 产物构造旧文件；只操作专用测试库，不连接生产 blb.db。
 */
@RunWith(AndroidJUnit4.class)
public class Migration6To7Test {

    private static final String MIGRATED_NAME = "blb-migration-6-7-test.db";
    private static final String FRESH_NAME = "blb-fresh-7-test.db";
    private static final String FULL_LOG_QUERY = "SELECT * FROM check_in_log ORDER BY id";
    private static final String SCHEMA_QUERY =
            "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name";

    // 历史 schema 必须保留已删除列的原名，否则测试会绕过这次真正需要验证的重建。
    private static final String[] V6_SCHEMA = {
            "CREATE TABLE account (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "label TEXT, login_name TEXT NOT NULL, login_kind TEXT NOT NULL, "
                    + "enc_password BLOB, enc_iv BLOB, nickname TEXT, last_check_in_at INTEGER NOT NULL, "
                    + "last_known_coupons INTEGER NOT NULL, last_known_vouchers INTEGER NOT NULL, "
                    + "enabled INTEGER NOT NULL, sort_order INTEGER NOT NULL, note TEXT)",
            "CREATE UNIQUE INDEX index_account_login_name ON account (login_name)",
            "CREATE TABLE novel (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "sf_novel_id TEXT, title TEXT NOT NULL, author TEXT, note TEXT, "
                    + "is_target INTEGER NOT NULL, start_chapter_no INTEGER NOT NULL, "
                    + "catalog_scanned_at INTEGER NOT NULL DEFAULT 0, "
                    + "catalog_chapter_count INTEGER NOT NULL DEFAULT 0)",
            "CREATE UNIQUE INDEX index_novel_sf_novel_id ON novel (sf_novel_id)",
            "CREATE TABLE chapter (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "novel_id INTEGER NOT NULL, chapter_no INTEGER NOT NULL, title TEXT, "
                    + "sf_chapter_id TEXT, price_coupons INTEGER NOT NULL, "
                    + "FOREIGN KEY(novel_id) REFERENCES novel(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX index_chapter_novel_id_chapter_no ON chapter (novel_id, chapter_no)",
            "CREATE TABLE purchase (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "account_id INTEGER NOT NULL, chapter_id INTEGER NOT NULL, "
                    + "cost_coupons INTEGER NOT NULL, cost_vouchers INTEGER NOT NULL, "
                    + "purchased_at INTEGER NOT NULL, source TEXT, "
                    + "FOREIGN KEY(account_id) REFERENCES account(id) ON UPDATE NO ACTION ON DELETE CASCADE, "
                    + "FOREIGN KEY(chapter_id) REFERENCES chapter(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX index_purchase_account_id_chapter_id ON purchase (account_id, chapter_id)",
            "CREATE INDEX index_purchase_chapter_id ON purchase (chapter_id)",
            "CREATE TABLE check_in_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "account_id INTEGER NOT NULL, date_ymd TEXT, status TEXT, "
                    + "ad_available INTEGER NOT NULL, ads_watched INTEGER NOT NULL, "
                    + "ads_remaining INTEGER NOT NULL, message TEXT, created_at INTEGER NOT NULL, "
                    + "FOREIGN KEY(account_id) REFERENCES account(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX index_check_in_log_account_id_date_ymd "
                    + "ON check_in_log (account_id, date_ymd)",
            "CREATE TABLE account_novel_audit (account_id INTEGER NOT NULL, novel_id INTEGER NOT NULL, "
                    + "aggregate_at INTEGER NOT NULL DEFAULT 0, aggregate_chapters INTEGER NOT NULL DEFAULT -1, "
                    + "detail_at INTEGER NOT NULL DEFAULT 0, detail_chapters INTEGER NOT NULL DEFAULT -1, "
                    + "ledger_marker TEXT, PRIMARY KEY(account_id, novel_id))",
            "CREATE TABLE ledger_audit (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "at INTEGER NOT NULL, account_id INTEGER NOT NULL, novel_id INTEGER NOT NULL, "
                    + "kind TEXT NOT NULL, chapter_id INTEGER NOT NULL DEFAULT 0, "
                    + "chapter_no INTEGER NOT NULL DEFAULT 0, title TEXT, detail TEXT)",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT INTO room_master_table (id, identity_hash) "
                    + "VALUES (42, '41d7b3fe08b4ba3d2d72e0f6dde3710c')"
    };

    // 签到只删指定统计列；其他表逐列比较，不能以总行数没变代替真实内容没变。
    private static final String[] RETAINED_QUERIES = {
            "SELECT * FROM account ORDER BY id",
            "SELECT * FROM novel ORDER BY id",
            "SELECT id, novel_id, chapter_no, title, sf_chapter_id, price_coupons FROM chapter ORDER BY id",
            "SELECT * FROM purchase ORDER BY id",
            "SELECT id, account_id, date_ymd, status, message, created_at FROM check_in_log ORDER BY id",
            "SELECT * FROM account_novel_audit ORDER BY account_id, novel_id",
            "SELECT * FROM ledger_audit ORDER BY id"
    };

    private Context context;
    private AppDatabase migrated;
    private AppDatabase fresh;

    @Before
    public void prepareIsolatedFiles() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(MIGRATED_NAME);
        context.deleteDatabase(FRESH_NAME);
    }

    @After
    public void closeIsolatedFiles() {
        if (migrated != null) migrated.close();
        if (fresh != null) fresh.close();
        context.deleteDatabase(MIGRATED_NAME);
        context.deleteDatabase(FRESH_NAME);
    }

    @Test
    public void upgradePreservesEveryHistoricalRowAndReopens() {
        Snapshot before = createVersion6Fixture();
        migrated = openDatabase(MIGRATED_NAME);
        SupportSQLiteDatabase sql = migrated.getOpenHelper().getWritableDatabase();
        assertEquals(7, sql.getVersion());
        assertRetainedRows(before, sql);
        assertNoForeignKeyViolations(sql);
        assertEquals(5, migrated.checkInDao().loadRecent(20).size());
        assertEquals(2, migrated.accountDao().loadAll().size());
        assertEquals(2, migrated.subscriptionDao().loadPurchasesOfNovel(101L).size());
        assertNotNull(migrated.checkInDao().find(11L, "2026-09-13"));
        assertEquals("ALREADY", migrated.checkInDao().find(11L, "2026-09-13").status);
        for (Chapter chapter : migrated.subscriptionDao().loadChapters(101L)) {
            assertNull("旧行没有读过卷名，不得伪造", chapter.volumeTitle);
        }

        migrated.close();
        migrated = openDatabase(MIGRATED_NAME);
        assertRetainedRows(before, migrated.getOpenHelper().getWritableDatabase());
        assertEquals("1:302", migrated.auditDao().paidLedgerMarker(11L, 101L));
        assertEquals("1:303", migrated.auditDao().paidLedgerMarker(22L, 101L));
        assertEquals("旧核对依据", migrated.auditDao().ledgerAuditById(501L).detail);
    }

    @Test
    public void rebuiltTableMatchesFreshSchemaAndKeepsUniqueIndexAndForeignKey() {
        createVersion6Fixture();
        migrated = openDatabase(MIGRATED_NAME);
        fresh = openDatabase(FRESH_NAME);
        SupportSQLiteDatabase upgraded = migrated.getOpenHelper().getWritableDatabase();
        SupportSQLiteDatabase created = fresh.getOpenHelper().getWritableDatabase();
        for (String table : new String[]{"account", "novel", "chapter", "purchase", "check_in_log",
                "account_novel_audit", "ledger_audit"}) {
            assertEquals(table, columnDefinitions(created, table), columnDefinitions(upgraded, table));
            for (String pragma : new String[]{"index_list", "foreign_key_list"}) {
                String query = "PRAGMA " + pragma + "(`" + table + "`)";
                assertEquals(query, readRows(created.query(query)), readRows(upgraded.query(query)));
            }
        }
        for (SupportSQLiteDatabase sql : new SupportSQLiteDatabase[]{upgraded, created}) {
            Map<String, List<String>> expected = new TreeMap<>();
            expected.put("id", Arrays.asList("INTEGER", "1", null, "1"));
            expected.put("account_id", Arrays.asList("INTEGER", "1", null, "0"));
            expected.put("date_ymd", Arrays.asList("TEXT", "0", null, "0"));
            expected.put("status", Arrays.asList("TEXT", "0", null, "0"));
            expected.put("message", Arrays.asList("TEXT", "0", null, "0"));
            expected.put("created_at", Arrays.asList("INTEGER", "1", null, "0"));
            assertEquals(expected, columnDefinitions(sql, "check_in_log"));
            assertEquals(Arrays.asList("TEXT", "0", null, "0"),
                    columnDefinitions(sql, "chapter").get("volume_title"));
            assertCheckInIndexAndForeignKey(sql);
            assertNoForeignKeyViolations(sql);
        }

        expectConstraint(upgraded, "INSERT INTO check_in_log "
                + "(id, account_id, date_ymd, status, created_at) "
                + "VALUES (900, 11, '2026-09-13', 'OK', 123)");
        expectConstraint(upgraded, "INSERT INTO check_in_log "
                + "(id, account_id, date_ymd, status, created_at) "
                + "VALUES (901, 999, '2026-09-14', 'OK', 123)");

        // 单独父行没有任何购买；这里只验证重建后签到外键仍执行级联，不经过业务删账入口。
        upgraded.execSQL("INSERT INTO account (id, login_name, login_kind, last_check_in_at, "
                + "last_known_coupons, last_known_vouchers, enabled, sort_order) "
                + "VALUES (33, 'migration-fk-parent', 'PASSWORD', 0, -1, -1, 0, 9)");
        CheckInLog next = new CheckInLog();
        next.accountId = 33L;
        next.dateYmd = "2026-09-14";
        next.status = CheckInLog.OK;
        next.createdAt = 1700000090000L;
        assertTrue("重建后自增键仍在保留的历史 id 之后", migrated.checkInDao().upsert(next) > 405L);
        upgraded.execSQL("DELETE FROM account WHERE id = 33");
        assertNull(migrated.checkInDao().find(33L, "2026-09-14"));
        assertEquals(5, migrated.checkInDao().loadRecent(20).size());
        assertNoForeignKeyViolations(upgraded);
    }

    @Test
    public void failureAfterRebuildRollsBackSchemaRowsAndVersion() {
        Snapshot before = createVersion6Fixture();
        Migration interrupted = new Migration(6, 7) {
            @Override
            public void migrate(@NonNull SupportSQLiteDatabase db) {
                Db.MIGRATION_6_7.migrate(db);
                throw new IllegalStateException("forced-migration-rollback");
            }
        };
        AppDatabase rejected = Room.databaseBuilder(context, AppDatabase.class, MIGRATED_NAME)
                .addMigrations(interrupted).allowMainThreadQueries().build();
        try {
            rejected.getOpenHelper().getWritableDatabase();
            fail("失败的升级不能把新版本或半张表留下");
        } catch (RuntimeException expected) {
            assertTrue(expected.toString(), causedByRollbackProbe(expected));
        } finally {
            rejected.close();
        }
        try (SQLiteDatabase old = context.openOrCreateDatabase(MIGRATED_NAME, Context.MODE_PRIVATE, null)) {
            assertEquals(6, old.getVersion());
            assertEquals(before.fullLogs, readRows(old.rawQuery(FULL_LOG_QUERY, null)));
            assertEquals(before.schema, readRows(old.rawQuery(SCHEMA_QUERY, null)));
            for (Map.Entry<String, List<List<String>>> entry : before.rows.entrySet()) {
                assertEquals(entry.getKey(), entry.getValue(), readRows(old.rawQuery(entry.getKey(), null)));
            }
        }
        // 失败后安装修正后的升级逻辑即可重试，不需要清数据或向下重装旧版。
        migrated = openDatabase(MIGRATED_NAME);
        assertRetainedRows(before, migrated.getOpenHelper().getWritableDatabase());
    }

    private AppDatabase openDatabase(String name) {
        AppDatabase database = Room.databaseBuilder(context, AppDatabase.class, name)
                .addMigrations(Db.MIGRATION_6_7).allowMainThreadQueries().build();
        try {
            database.getOpenHelper().getWritableDatabase();
            return database;
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
        }
    }

    private Snapshot createVersion6Fixture() {
        Snapshot before = new Snapshot();
        try (SQLiteDatabase old = context.openOrCreateDatabase(MIGRATED_NAME, Context.MODE_PRIVATE, null)) {
            old.setForeignKeyConstraintsEnabled(true);
            old.beginTransaction();
            try {
                for (String statement : V6_SCHEMA) old.execSQL(statement);
                old.execSQL("INSERT INTO account VALUES "
                        + "(11, '主号', 'migration-main', 'PASSWORD', X'0001FF11', X'09080700', "
                        + "'老昵称', 1700000000123, 12, 123, 1, 3, '不能丢的备注'), "
                        + "(22, NULL, 'migration-disabled', 'PHONE_ONE_TAP', NULL, NULL, NULL, "
                        + "0, -1, -1, 0, 8, NULL)");
                old.execSQL("INSERT INTO novel VALUES "
                        + "(101, 'sf-101', '迁移前目标书', '作者', '备注', 1, 2, 1700000000100, 2), "
                        + "(102, NULL, '另一书', NULL, NULL, 0, 1, 0, 0)");
                old.execSQL("INSERT INTO chapter VALUES "
                        + "(201, 101, 1, '免费序章', 'sf-1', 0), "
                        + "(202, 101, 2, '真买章', 'sf-2', 19), "
                        + "(203, 102, 1, NULL, NULL, 0)");
                old.execSQL("INSERT INTO purchase VALUES "
                        + "(302, 11, 202, 0, 19, 1700000001000, 'AUTO'), "
                        + "(303, 22, 202, 0, 19, 1700000002000, 'REMOTE_DETAIL'), "
                        + "(304, 11, 203, 0, 0, 1700000003000, 'OWNED')");
                old.execSQL("INSERT INTO check_in_log VALUES "
                        + "(401, 11, '2026-09-12', 'OK', 1, 2, -1, '旧签到日志', 1700000000000), "
                        + "(402, 11, '2026-09-13', 'ALREADY', 0, 0, 0, NULL, 1700086400000), "
                        + "(403, 22, '2026-09-13', 'FAILED', 1, 3, 2, '', 1700086400100), "
                        + "(404, 22, NULL, NULL, 0, 0, -1, '旧空值', 0), "
                        + "(405, 22, NULL, 'SKIPPED', 0, 1, -1, NULL, 1700086400200)");
                old.execSQL("INSERT INTO account_novel_audit VALUES "
                        + "(11, 101, 1700000004000, 1, 1700000005000, 1, '1:302'), "
                        + "(22, 101, 1700000006000, 1, 1700000007000, 1, '1:303'), "
                        + "(11, 102, 0, -1, 0, -1, NULL)");
                old.execSQL("INSERT INTO ledger_audit VALUES "
                        + "(501, 1700000008000, 22, 101, 'BACKFILL', 202, 2, '真买章', '旧核对依据')");
                old.setVersion(6);
                old.setTransactionSuccessful();
            } finally {
                old.endTransaction();
            }
            assertEquals(6, old.getVersion());
            for (String query : RETAINED_QUERIES) {
                before.rows.put(query, readRows(old.rawQuery(query, null)));
            }
            before.fullLogs = readRows(old.rawQuery(FULL_LOG_QUERY, null));
            before.schema = readRows(old.rawQuery(SCHEMA_QUERY, null));
        }
        return before;
    }

    private static final class Snapshot {
        final Map<String, List<List<String>>> rows = new LinkedHashMap<>();
        List<List<String>> fullLogs;
        List<List<String>> schema;
    }

    private static void assertRetainedRows(Snapshot before, SupportSQLiteDatabase sql) {
        for (Map.Entry<String, List<List<String>>> entry : before.rows.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue(), readRows(sql.query(entry.getKey())));
        }
    }

    private static Map<String, List<String>> columnDefinitions(SupportSQLiteDatabase sql, String table) {
        Map<String, List<String>> columns = new TreeMap<>();
        try (Cursor cursor = sql.query("PRAGMA table_info(`" + table + "`)")) {
            while (cursor.moveToNext()) {
                columns.put(cursor.getString(cursor.getColumnIndexOrThrow("name")), Arrays.asList(
                        cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        cursor.getString(cursor.getColumnIndexOrThrow("notnull")),
                        cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                        cursor.getString(cursor.getColumnIndexOrThrow("pk"))));
            }
        }
        return columns;
    }

    private static void assertCheckInIndexAndForeignKey(SupportSQLiteDatabase sql) {
        try (Cursor cursor = sql.query("PRAGMA index_list(check_in_log)")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("index_check_in_log_account_id_date_ymd",
                    cursor.getString(cursor.getColumnIndexOrThrow("name")));
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("unique")));
            assertFalse(cursor.moveToNext());
        }
        List<String> indexedColumns = new ArrayList<>();
        try (Cursor cursor = sql.query("PRAGMA index_info(index_check_in_log_account_id_date_ymd)")) {
            while (cursor.moveToNext()) indexedColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
        }
        assertEquals(Arrays.asList("account_id", "date_ymd"), indexedColumns);
        try (Cursor cursor = sql.query("PRAGMA foreign_key_list(check_in_log)")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("account", cursor.getString(cursor.getColumnIndexOrThrow("table")));
            assertEquals("account_id", cursor.getString(cursor.getColumnIndexOrThrow("from")));
            assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")));
            assertEquals("NO ACTION", cursor.getString(cursor.getColumnIndexOrThrow("on_update")));
            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")));
            assertFalse(cursor.moveToNext());
        }
    }

    private static void assertNoForeignKeyViolations(SupportSQLiteDatabase sql) {
        try (Cursor cursor = sql.query("PRAGMA foreign_key_check")) {
            assertFalse("迁移后不能有孤立历史行", cursor.moveToFirst());
        }
    }

    private static void expectConstraint(SupportSQLiteDatabase sql, String statement) {
        try {
            sql.execSQL(statement);
            fail("重建后必须仍拒绝违反唯一键或外键的行");
        } catch (SQLiteConstraintException expected) {
            // 约束仍由数据库执行，不能只靠界面约定。
        }
    }

    private static boolean causedByRollbackProbe(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains("forced-migration-rollback")) return true;
        }
        return false;
    }

    private static List<List<String>> readRows(Cursor cursor) {
        List<List<String>> rows = new ArrayList<>();
        try (Cursor closeable = cursor) {
            while (closeable.moveToNext()) {
                List<String> row = new ArrayList<>();
                for (int column = 0; column < closeable.getColumnCount(); column++) {
                    int type = closeable.getType(column);
                    if (type == Cursor.FIELD_TYPE_NULL) row.add(null);
                    else if (type == Cursor.FIELD_TYPE_BLOB) {
                        row.add(type + ":" + Arrays.toString(closeable.getBlob(column)));
                    } else row.add(type + ":" + closeable.getString(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
