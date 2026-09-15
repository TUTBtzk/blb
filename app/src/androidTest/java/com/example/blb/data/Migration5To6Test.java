package com.example.blb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
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

/**
 * 真机覆盖安装必须留下原账号和真账本；内存中新建库无法证明老库能升级。
 * 2026-09-14 升到 v7 后仍单独核验历史 5→6，再验证到当前版本的完整迁移链。
 * 使用专属文件名，避免测试误连 Db.get() 的生产 blb.db。
 */
@RunWith(AndroidJUnit4.class)
public class Migration5To6Test {

    private static final String MIGRATED_NAME = "blb-migration-5-6-test.db";
    private static final String FRESH_NAME = "blb-fresh-from-5-test.db";

    // 冻结自改版本前的 v5 Room 产物；若改用当前实体建库，就会跳过待验证的迁移。
    private static final String[] V5_SCHEMA = {
            "CREATE TABLE account (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "label TEXT, login_name TEXT NOT NULL, login_kind TEXT NOT NULL, "
                    + "enc_password BLOB, enc_iv BLOB, nickname TEXT, last_check_in_at INTEGER NOT NULL, "
                    + "last_known_coupons INTEGER NOT NULL, last_known_vouchers INTEGER NOT NULL, "
                    + "enabled INTEGER NOT NULL, sort_order INTEGER NOT NULL, note TEXT)",
            "CREATE UNIQUE INDEX index_account_login_name ON account (login_name)",
            "CREATE TABLE novel (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "sf_novel_id TEXT, title TEXT NOT NULL, author TEXT, note TEXT, "
                    + "is_target INTEGER NOT NULL, start_chapter_no INTEGER NOT NULL)",
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
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT INTO room_master_table (id, identity_hash) "
                    + "VALUES (42, '599ba20a574a14518c18389a7094226b')"
    };

    // 明确取老列，新增的扫描字段不应遮住任何旧值被改坏的问题，BLOB 与 NULL 也逐格比较。
    private static final String[] LEGACY_QUERIES = {
            "SELECT id, label, login_name, login_kind, enc_password, enc_iv, nickname, "
                    + "last_check_in_at, last_known_coupons, last_known_vouchers, enabled, sort_order, note "
                    + "FROM account ORDER BY id",
            "SELECT id, sf_novel_id, title, author, note, is_target, start_chapter_no FROM novel ORDER BY id",
            "SELECT id, novel_id, chapter_no, title, sf_chapter_id, price_coupons FROM chapter ORDER BY id",
            "SELECT id, account_id, chapter_id, cost_coupons, cost_vouchers, purchased_at, source "
                    + "FROM purchase ORDER BY id",
            "SELECT id, account_id, date_ymd, status, message, created_at FROM check_in_log ORDER BY id"
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
    public void historicalVersion5To6PreservesFullRowsBeforeFollowingVersion7() {
        Map<String, List<List<String>>> before = createVersion5Fixture();
        List<List<String>> fullLogs;
        List<List<String>> logColumns;
        try (SQLiteDatabase old = context.openOrCreateDatabase(MIGRATED_NAME, Context.MODE_PRIVATE, null)) {
            fullLogs = readRows(old.rawQuery("SELECT * FROM check_in_log ORDER BY id", null));
            logColumns = readRows(old.rawQuery("PRAGMA table_info(check_in_log)", null));
        }
        SupportSQLiteOpenHelper helper = new FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(MIGRATED_NAME)
                        .callback(new SupportSQLiteOpenHelper.Callback(6) {
                            @Override
                            public void onCreate(SupportSQLiteDatabase db) {
                                throw new AssertionError("必须使用已有 v5 文件");
                            }

                            @Override
                            public void onUpgrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
                                assertEquals(5, oldVersion);
                                assertEquals(6, newVersion);
                                Db.MIGRATION_5_6.migrate(db);
                            }
                        }).build());
        try {
            SupportSQLiteDatabase sql = helper.getWritableDatabase();
            assertEquals(6, sql.getVersion());
            assertLegacyRows(before, sql);
            assertEquals(fullLogs, readRows(sql.query("SELECT * FROM check_in_log ORDER BY id")));
            assertEquals(logColumns, readRows(sql.query("PRAGMA table_info(check_in_log)")));
            assertColumn(sql, "novel", "catalog_scanned_at", 1, 0, "0");
            assertColumn(sql, "novel", "catalog_chapter_count", 1, 0, "0");
            assertColumn(sql, "ledger_audit", "id", 1, 1, null);
            assertColumn(sql, "account_novel_audit", "detail_chapters", 1, 0, "-1");
            assertNoForeignKeyViolations(sql);
        } finally {
            helper.close();
        }
        migrated = openDatabase(MIGRATED_NAME);
        assertEquals(7, migrated.getOpenHelper().getWritableDatabase().getVersion());
        assertLegacyRows(before, migrated.getOpenHelper().getWritableDatabase());
    }

    @Test
    public void upgradePreservesEveryOldColumnAndReopens() {
        Map<String, List<List<String>>> before = createVersion5Fixture();
        migrated = openDatabase(MIGRATED_NAME);
        SupportSQLiteDatabase sql = migrated.getOpenHelper().getWritableDatabase();

        assertEquals(7, sql.getVersion());
        assertLegacyRows(before, sql);
        assertNoForeignKeyViolations(sql);
        assertEquals(2, migrated.accountDao().loadAll().size());
        assertEquals(1, migrated.accountDao().loadEnabled().size());
        assertEquals(101L, migrated.subscriptionDao().targetNovel().id);
        assertEquals(2, migrated.subscriptionDao().targetNovel().startFrom());
        for (long novelId : new long[]{101L, 102L}) {
            Novel novel = migrated.subscriptionDao().novelById(novelId);
            assertEquals(0L, novel.catalogScannedAt);
            assertEquals(0, novel.catalogChapterCount);
            assertTrue(migrated.auditDao().loadProgressOfNovel(novelId).isEmpty());
            assertTrue(migrated.auditDao().loadRecentLedgerAudits(novelId, 20).isEmpty());
        }

        migrated.close();
        migrated = openDatabase(MIGRATED_NAME);
        assertLegacyRows(before, migrated.getOpenHelper().getWritableDatabase());
        assertEquals("2:304", migrated.auditDao().paidLedgerMarker(11L, 101L));
        assertEquals("1:303", migrated.auditDao().paidLedgerMarker(22L, 101L));
        assertEquals("0:0", migrated.auditDao().paidLedgerMarker(22L, 102L));
    }

    @Test
    public void upgradedAndFreshSchemasAgreeIncludingDefaultsAndPrimaryKeys() {
        createVersion5Fixture();
        migrated = openDatabase(MIGRATED_NAME);
        fresh = openDatabase(FRESH_NAME);
        SupportSQLiteDatabase upgradedSql = migrated.getOpenHelper().getWritableDatabase();
        SupportSQLiteDatabase freshSql = fresh.getOpenHelper().getWritableDatabase();

        for (String table : new String[]{"account", "novel", "chapter", "purchase", "check_in_log",
                "account_novel_audit", "ledger_audit"}) {
            for (String pragma : new String[]{"table_info", "index_list", "foreign_key_list"}) {
                String query = "PRAGMA " + pragma + "(`" + table + "`)";
                assertEquals(query, readRows(freshSql.query(query)), readRows(upgradedSql.query(query)));
            }
        }
        for (SupportSQLiteDatabase sql : new SupportSQLiteDatabase[]{upgradedSql, freshSql}) {
            assertColumn(sql, "ledger_audit", "id", 1, 1, null);
            assertColumn(sql, "account_novel_audit", "account_id", 1, 1, null);
            assertColumn(sql, "account_novel_audit", "novel_id", 1, 2, null);
            assertColumn(sql, "novel", "catalog_scanned_at", 1, 0, "0");
            assertColumn(sql, "novel", "catalog_chapter_count", 1, 0, "0");
            assertColumn(sql, "account_novel_audit", "aggregate_at", 1, 0, "0");
            assertColumn(sql, "account_novel_audit", "aggregate_chapters", 1, 0, "-1");
            assertColumn(sql, "account_novel_audit", "detail_at", 1, 0, "0");
            assertColumn(sql, "account_novel_audit", "detail_chapters", 1, 0, "-1");
            assertColumn(sql, "ledger_audit", "chapter_id", 1, 0, "0");
            assertColumn(sql, "ledger_audit", "chapter_no", 1, 0, "0");
        }
        assertSqlDefaults(migrated);
        assertSqlDefaults(fresh);
        assertNoForeignKeyViolations(upgradedSql);
        assertNoForeignKeyViolations(freshSql);
    }

    @Test
    public void savingAuditEvidenceNeverChangesExistingPurchases() {
        Map<String, List<List<String>>> before = createVersion5Fixture();
        migrated = openDatabase(MIGRATED_NAME);
        AuditDao audits = migrated.auditDao();

        AccountNovelAudit progress = new AccountNovelAudit();
        progress.accountId = 11L;
        progress.novelId = 101L;
        progress.aggregateAt = 1_700_000_010_000L;
        progress.aggregateChapters = 2;
        audits.saveProgress(progress);
        progress.detailAt = 1_700_000_020_000L;
        progress.detailChapters = 2;
        progress.ledgerMarker = audits.paidLedgerMarker(11L, 101L);
        audits.saveProgress(progress);
        assertEquals(1, audits.loadProgressOfNovel(101L).size());

        LedgerAudit audit = new LedgerAudit();
        audit.at = progress.detailAt;
        audit.accountId = 11L;
        audit.novelId = 101L;
        audit.kind = LedgerAudit.KIND_SUSPECT;
        audit.chapterId = 202L;
        audit.chapterNo = 2;
        audit.title = "真买章";
        audit.detail = "证据留在独立表，旧 purchase 不应改动";
        long auditId = audits.insertLedgerAudit(audit);
        assertTrue(auditId > 0);
        assertEquals(1, audits.setCatalogScan(101L, progress.detailAt, 4));
        assertLegacyRows(before, migrated.getOpenHelper().getWritableDatabase());

        migrated.close();
        migrated = openDatabase(MIGRATED_NAME);
        AccountNovelAudit storedProgress = migrated.auditDao().auditFor(11L, 101L);
        assertNotNull(storedProgress);
        assertEquals(progress.aggregateAt, storedProgress.aggregateAt);
        assertEquals(progress.aggregateChapters, storedProgress.aggregateChapters);
        assertEquals(progress.detailAt, storedProgress.detailAt);
        assertEquals(progress.detailChapters, storedProgress.detailChapters);
        assertEquals("2:304", storedProgress.ledgerMarker);
        LedgerAudit storedAudit = migrated.auditDao().ledgerAuditById(auditId);
        assertNotNull(storedAudit);
        assertEquals(auditId, storedAudit.id);
        assertEquals(audit.at, storedAudit.at);
        assertEquals(audit.accountId, storedAudit.accountId);
        assertEquals(audit.novelId, storedAudit.novelId);
        assertEquals(audit.kind, storedAudit.kind);
        assertEquals(audit.chapterId, storedAudit.chapterId);
        assertEquals(audit.chapterNo, storedAudit.chapterNo);
        assertEquals(audit.title, storedAudit.title);
        assertEquals(audit.detail, storedAudit.detail);
        assertEquals(auditId, migrated.auditDao().loadRecentLedgerAudits(101L, 1).get(0).id);
        assertEquals(progress.detailAt, migrated.subscriptionDao().novelById(101L).catalogScannedAt);
        assertEquals(4, migrated.subscriptionDao().novelById(101L).catalogChapterCount);
        assertLegacyRows(before, migrated.getOpenHelper().getWritableDatabase());
    }

    private AppDatabase openDatabase(String name) {
        AppDatabase database = Room.databaseBuilder(context, AppDatabase.class, name)
                .addMigrations(Db.MIGRATION_5_6, Db.MIGRATION_6_7)
                .allowMainThreadQueries()
                .build();
        try {
            // build() 本身不打开文件；必须强制打开，才会真正执行升级及 Room 的逐列校验。
            database.getOpenHelper().getWritableDatabase();
            return database;
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
        }
    }

    private Map<String, List<List<String>>> createVersion5Fixture() {
        Map<String, List<List<String>>> before = new LinkedHashMap<>();
        try (SQLiteDatabase legacy = context.openOrCreateDatabase(MIGRATED_NAME, Context.MODE_PRIVATE, null)) {
            legacy.setForeignKeyConstraintsEnabled(true);
            legacy.beginTransaction();
            try {
                for (String statement : V5_SCHEMA) legacy.execSQL(statement);
                legacy.execSQL("INSERT INTO account VALUES "
                        + "(11, '主号', 'migration-main', 'PASSWORD', X'0001FF11', X'09080700', "
                        + "'老昵称', 1700000000123, 12, 123, 1, 3, '不能丢的账号备注'), "
                        + "(22, NULL, 'migration-disabled', 'PHONE_ONE_TAP', NULL, NULL, NULL, "
                        + "0, -1, -1, 0, 8, NULL)");
                legacy.execSQL("INSERT INTO novel VALUES "
                        + "(101, 'sf-target-101', '迁移前目标书', '作者甲', '保留起始章', 1, 2), "
                        + "(102, NULL, '未选书', NULL, NULL, 0, 1)");
                legacy.execSQL("INSERT INTO chapter VALUES "
                        + "(201, 101, 1, '免费序章', 'sf-1', 0), "
                        + "(202, 101, 2, '真买章', 'sf-2', 19), "
                        + "(203, 101, 3, '手记章', 'sf-3', 23), "
                        + "(204, 101, 4, '明细补回', 'sf-4', 27), "
                        + "(205, 102, 1, NULL, NULL, 0)");
                legacy.execSQL("INSERT INTO purchase VALUES "
                        + "(301, 11, 201, 0, 0, 1699999900000, 'OWNED'), "
                        + "(302, 11, 202, 0, 19, 1700000001000, 'AUTO'), "
                        + "(303, 22, 203, 23, 0, 1700000002000, 'MANUAL'), "
                        + "(304, 11, 204, 0, 27, 1700000003000, 'REMOTE_DETAIL'), "
                        + "(305, 22, 201, 0, 0, 1700000004000, 'OWNED'), "
                        + "(306, 22, 205, 0, 0, 1700000005000, NULL)");
                legacy.execSQL("INSERT INTO check_in_log VALUES "
                        + "(401, 11, '2026-09-13', 'OK', 1, 2, -1, '旧签到日志', 1700000000000), "
                        + "(402, 22, '2026-09-12', 'FAILED', 0, 0, -1, NULL, 1699913600000)");
                legacy.setVersion(5);
                legacy.setTransactionSuccessful();
            } finally {
                legacy.endTransaction();
            }
            assertEquals(5, legacy.getVersion());
            for (String query : LEGACY_QUERIES) before.put(query, readRows(legacy.rawQuery(query, null)));
        }
        return before;
    }

    private static void assertLegacyRows(Map<String, List<List<String>>> before, SupportSQLiteDatabase sql) {
        for (Map.Entry<String, List<List<String>>> entry : before.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue(), readRows(sql.query(entry.getKey())));
        }
    }

    private static void assertNoForeignKeyViolations(SupportSQLiteDatabase sql) {
        try (Cursor cursor = sql.query("PRAGMA foreign_key_check")) {
            assertFalse("迁移不能留下失去账号或章节的账本行", cursor.moveToFirst());
        }
    }

    private static void assertColumn(SupportSQLiteDatabase sql, String table, String column,
                                     int notNull, int primaryKeyPosition, String defaultValue) {
        try (Cursor cursor = sql.query("PRAGMA table_info(`" + table + "`)")) {
            while (cursor.moveToNext()) {
                if (!column.equals(cursor.getString(cursor.getColumnIndexOrThrow("name")))) continue;
                assertEquals(table + "." + column, "INTEGER",
                        cursor.getString(cursor.getColumnIndexOrThrow("type")));
                assertEquals(table + "." + column, notNull,
                        cursor.getInt(cursor.getColumnIndexOrThrow("notnull")));
                assertEquals(table + "." + column, primaryKeyPosition,
                        cursor.getInt(cursor.getColumnIndexOrThrow("pk")));
                assertEquals(table + "." + column, defaultValue,
                        cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")));
                return;
            }
        }
        throw new AssertionError("缺列 " + table + "." + column);
    }

    private static void assertSqlDefaults(AppDatabase database) {
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        sql.execSQL("INSERT INTO account_novel_audit (account_id, novel_id) VALUES (11, 101)");
        AccountNovelAudit progress = database.auditDao().auditFor(11L, 101L);
        assertNotNull(progress);
        assertEquals(0L, progress.aggregateAt);
        assertEquals(-1, progress.aggregateChapters);
        assertEquals(0L, progress.detailAt);
        assertEquals(-1, progress.detailChapters);
        assertNull(progress.ledgerMarker);

        sql.execSQL("INSERT INTO ledger_audit (at, account_id, novel_id, kind) "
                + "VALUES (1700000020000, 11, 101, 'SUSPECT')");
        LedgerAudit audit = database.auditDao().loadRecentLedgerAudits(101L, 1).get(0);
        assertTrue(audit.id > 0);
        assertEquals(LedgerAudit.KIND_SUSPECT, audit.kind);
        assertEquals(0L, audit.chapterId);
        assertEquals(0, audit.chapterNo);
        assertNull(audit.title);
        assertNull(audit.detail);
    }

    private static List<List<String>> readRows(Cursor cursor) {
        List<List<String>> rows = new ArrayList<>();
        try (Cursor closeable = cursor) {
            while (closeable.moveToNext()) {
                List<String> row = new ArrayList<>();
                for (int column = 0; column < closeable.getColumnCount(); column++) {
                    int type = closeable.getType(column);
                    if (type == Cursor.FIELD_TYPE_NULL) {
                        row.add(null);
                    } else if (type == Cursor.FIELD_TYPE_BLOB) {
                        row.add(type + ":" + Arrays.toString(closeable.getBlob(column)));
                    } else {
                        row.add(type + ":" + closeable.getString(column));
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
