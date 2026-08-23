package com.example.blb.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AccountDao {

    @Query("SELECT * FROM account ORDER BY sort_order ASC, id ASC")
    LiveData<List<Account>> observeAll();

    @Query("SELECT * FROM account ORDER BY sort_order ASC, id ASC")
    List<Account> loadAll();

    /** 自动化队列只跑启用的账号。 */
    @Query("SELECT * FROM account WHERE enabled = 1 ORDER BY sort_order ASC, id ASC")
    List<Account> loadEnabled();

    /** 只启用一个号时，「现在登着的」就只能是它 —— 首次回填昵称靠这个判断。 */
    @Query("SELECT COUNT(*) FROM account WHERE enabled = 1")
    int countEnabled();

    @Query("SELECT * FROM account WHERE id = :id")
    Account byId(long id);

    @Query("SELECT * FROM account WHERE nickname = :nickname LIMIT 1")
    Account byNickname(String nickname);

    /** CSV 导入时按登录名对号，找不到就跳过那一行（不凭空造没有密码的账号）。 */
    @Query("SELECT * FROM account WHERE login_name = :loginName LIMIT 1")
    Account byLoginName(String loginName);

    @Query("SELECT IFNULL(MAX(sort_order), 0) FROM account")
    int maxSortOrder();

    @Insert
    long insert(Account account);

    @Update
    void update(Account account);

    @Delete
    void delete(Account account);

    @Query("UPDATE account SET nickname = :nickname WHERE id = :id")
    void setNickname(long id, String nickname);

    @Query("UPDATE account SET last_known_coupons = :coupons WHERE id = :id")
    void setCoupons(long id, int coupons);

    /** 火券和代券一起回填。读不到的那一项传 -1，SQL 里用 CASE 保留原值。 */
    @Query("UPDATE account SET "
            + "last_known_coupons = CASE WHEN :coupons >= 0 THEN :coupons ELSE last_known_coupons END, "
            + "last_known_vouchers = CASE WHEN :vouchers >= 0 THEN :vouchers ELSE last_known_vouchers END "
            + "WHERE id = :id")
    void setBalance(long id, int coupons, int vouchers);

    @Query("UPDATE account SET last_check_in_at = :at WHERE id = :id")
    void setLastCheckInAt(long id, long at);
}
