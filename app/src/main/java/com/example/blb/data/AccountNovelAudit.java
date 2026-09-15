package com.example.blb.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;

/**
 * 清单页写着约 5 分钟才更新一次，读到聚合行不能冒充逐章核过；两种证据必须各记各的时间。
 * 没读到的章数保留 -1，否则一次没加载出来就会被误当成「这个号一章都没买」。
 */
@Entity(tableName = "account_novel_audit", primaryKeys = {"account_id", "novel_id"})
public class AccountNovelAudit {

    @ColumnInfo(name = "account_id")
    public long accountId;

    @ColumnInfo(name = "novel_id")
    public long novelId;

    @ColumnInfo(name = "aggregate_at", defaultValue = "0")
    public long aggregateAt;

    @ColumnInfo(name = "aggregate_chapters", defaultValue = "-1")
    public int aggregateChapters = -1;

    @ColumnInfo(name = "detail_at", defaultValue = "0")
    public long detailAt;

    @ColumnInfo(name = "detail_chapters", defaultValue = "-1")
    public int detailChapters = -1;

    /** 账本变过就不能复用之前的逐章结论；删账仍须比较完整快照，不能只认这个简短指纹。 */
    @ColumnInfo(name = "ledger_marker")
    public String ledgerMarker;
}
