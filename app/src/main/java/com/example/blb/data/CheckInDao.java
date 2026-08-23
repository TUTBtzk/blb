package com.example.blb.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface CheckInDao {

    /** 幂等：同一账号同一天只留一条，重跑覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long upsert(CheckInLog log);

    @Query("SELECT * FROM check_in_log WHERE account_id = :accountId AND date_ymd = :ymd LIMIT 1")
    CheckInLog find(long accountId, String ymd);

    @Query("SELECT * FROM check_in_log WHERE date_ymd = :ymd ORDER BY created_at ASC")
    LiveData<List<CheckInLog>> observeByDate(String ymd);

    /**
     * 今日状态一览。从 account 出发 LEFT JOIN，所以还没跑到的启用账号也会列出来
     * （status 为 null，界面显示「今天还没跑」）。
     */
    @Query("SELECT a.id AS account_id, a.label AS account_label, a.nickname AS account_nickname, "
            + "a.login_name AS account_login, l.status AS status, "
            + "IFNULL(l.ad_available, 0) AS ad_available, "
            + "IFNULL(l.ads_watched, 0) AS ads_watched, "
            + "IFNULL(l.ads_remaining, -1) AS ads_remaining, "
            + "l.message AS message, "
            + "IFNULL(l.created_at, 0) AS created_at "
            + "FROM account a LEFT JOIN check_in_log l "
            + "  ON l.account_id = a.id AND l.date_ymd = :ymd "
            + "WHERE a.enabled = 1 "
            + "ORDER BY a.sort_order ASC, a.id ASC")
    LiveData<List<CheckInRow>> observeTodayStatus(String ymd);

    @Query("SELECT * FROM check_in_log ORDER BY created_at DESC LIMIT :limit")
    LiveData<List<CheckInLog>> observeRecent(int limit);

    @Query("SELECT * FROM check_in_log ORDER BY created_at DESC LIMIT :limit")
    List<CheckInLog> loadRecent(int limit);

    @Query("DELETE FROM check_in_log WHERE created_at < :before")
    int purgeOlderThan(long before);
}
