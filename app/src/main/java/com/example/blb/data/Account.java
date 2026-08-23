package com.example.blb.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 一个菠萝包账号。密码经 KeyStoreBox 用 AndroidKeyStore 里的 AES/GCM 密钥加密后
 * 以 (encPassword, encIv) 存放，明文不落盘。
 */
@Entity(tableName = "account", indices = {@Index(value = "login_name", unique = true)})
public class Account {

    /** 账号密码登录：唯一能全自动切号的方式。 */
    public static final String KIND_PASSWORD = "PASSWORD";
    /** 本机号码一键登录：登录页在 com.sfacg 里，勾同意后点一下就行。 */
    public static final String KIND_PHONE_ONE_TAP = "PHONE_ONE_TAP";
    /** 微信授权：授权页属于 com.tencent.mm，本 App 看不到，只能停下来等你点。 */
    public static final String KIND_WECHAT = "WECHAT";
    /** QQ 授权：授权页属于 com.tencent.mobileqq，同上。 */
    public static final String KIND_QQ = "QQ";
    /** 微博授权：授权页属于微博客户端，同上。 */
    public static final String KIND_WEIBO = "WEIBO";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 给自己看的备注名，例如「主号」。 */
    public String label;

    /** 登录用的手机号或用户名。 */
    @NonNull
    @ColumnInfo(name = "login_name")
    public String loginName = "";

    /**
     * 登录方式。只有 {@link #KIND_PASSWORD} 能全自动；一键登录要先勾「已阅读并同意」，
     * 三方授权的页面在别的 App 里，本 App 的无障碍范围只有 com.sfacg，看不见也点不了。
     */
    @NonNull
    @ColumnInfo(name = "login_kind")
    public String loginKind = KIND_PASSWORD;

    @ColumnInfo(name = "enc_password", typeAffinity = ColumnInfo.BLOB)
    public byte[] encPassword;

    @ColumnInfo(name = "enc_iv", typeAffinity = ColumnInfo.BLOB)
    public byte[] encIv;

    /** 菠萝包里显示的昵称，用来校验「当前登录的到底是哪个号」。首轮自动回填。 */
    public String nickname;

    @ColumnInfo(name = "last_check_in_at")
    public long lastCheckInAt;

    /** 火券余额。自动化跑完会回填，也可手填。 */
    @ColumnInfo(name = "last_known_coupons")
    public int lastKnownCoupons;

    /** 代券余额（签到和看广告发的就是这个）。-1 表示还没读到过。 */
    @ColumnInfo(name = "last_known_vouchers")
    public int lastKnownVouchers = -1;

    public boolean enabled = true;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    public String note;

    /** 界面上显示的名字：优先备注名，其次昵称，最后登录名。 */
    public String displayName() {
        if (label != null && !label.trim().isEmpty()) return label;
        if (nickname != null && !nickname.trim().isEmpty()) return nickname;
        return loginName;
    }

    public boolean hasPassword() {
        return encPassword != null && encPassword.length > 0 && encIv != null && encIv.length > 0;
    }

    /** 只有密码登录需要存密码；其余方式存了也用不上。 */
    public boolean needsPassword() {
        return KIND_PASSWORD.equals(loginKind);
    }

    /** 这种登录方式能不能不用人插手就切过去。 */
    public boolean canAutoLogin() {
        return KIND_PASSWORD.equals(loginKind) || KIND_PHONE_ONE_TAP.equals(loginKind);
    }

    public String loginKindLabel() {
        if (loginKind == null) return "未知";
        switch (loginKind) {
            case KIND_PASSWORD:
                return "账号密码";
            case KIND_PHONE_ONE_TAP:
                return "本机号码一键登录";
            case KIND_WECHAT:
                return "微信";
            case KIND_QQ:
                return "QQ";
            case KIND_WEIBO:
                return "微博";
            default:
                return loginKind;
        }
    }

    /** 余额一览，界面和日志共用。 */
    public String balanceText() {
        return "火券 " + lastKnownCoupons
                + (lastKnownVouchers >= 0 ? " / 代券 " + lastKnownVouchers : " / 代券 ?");
    }

    /** 估算能付章节费的券总量：两种券都能付，代券先扣。 */
    public int usableCoupons() {
        return Math.max(0, lastKnownCoupons) + Math.max(0, lastKnownVouchers);
    }
}
