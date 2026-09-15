package com.example.blb.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 修正错账会让一章重新进入待买队列，因此核对结论和原始记录要留下来；撤销不能再猜金额和日期。
 * 不挂级联外键，免得账号或小说被移除时连这份更正依据也一起消失。
 */
@Entity(tableName = "ledger_audit")
public class LedgerAudit {

    public static final String KIND_BACKFILL = "BACKFILL";
    public static final String KIND_DELETE = "DELETE";
    public static final String KIND_SUSPECT = "SUSPECT";
    public static final String KIND_RESTORE = "RESTORE";

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long at;

    @ColumnInfo(name = "account_id")
    public long accountId;

    @ColumnInfo(name = "novel_id")
    public long novelId;

    @NonNull
    public String kind = KIND_SUSPECT;

    @ColumnInfo(name = "chapter_id", defaultValue = "0")
    public long chapterId;

    @ColumnInfo(name = "chapter_no", defaultValue = "0")
    public int chapterNo;

    public String title;

    public String detail;
}
