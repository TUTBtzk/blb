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

    /** 账号密码登录：全自动，登录页 com.sf.login.LoginActivity。 */
    public static final String KIND_PASSWORD = "PASSWORD";
    /** 本机号码一键登录：登录页在 com.sfacg 里，勾同意后点一下就行。 */
    public static final String KIND_PHONE_ONE_TAP = "PHONE_ONE_TAP";
    /** 微信授权：实测点完图标就直接登回菠萝包了，没有中间的授权页。 */
    public static final String KIND_WECHAT = "WECHAT";
    /** QQ 授权：实测要在 com.tencent.mobileqq 里按一颗「同意」，见 Keys#LOGIN_AUTH_CONFIRM。 */
    public static final String KIND_QQ = "QQ";
    /** 微博授权：实测同微信，点完图标一步登入。 */
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
     * 登录方式。2026-08-23 起五种方式全部能不用人插手就切过去：账号密码走
     * com.sf.login.LoginActivity 的表单；一键登录和三方图标都在 com.sfacg 自己的
     * 一键登录页（com.mobile.auth.gatewayauth.LoginAuthActivity）上；QQ 那颗跨 App 的
     * 「同意」由无障碍的界外硬闸放行一小段时间去按。
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

    /**
     * 火券余额。自动化跑完会回填，也可手填。-1 表示还没读到过。
     *
     * <p>以前默认是 0，界面于是给「从来没读到过余额」的号也画出一个「火券 0」——
     * 那是编出来的数字，和真的读到 0 分不开。现在和代券统一：-1＝不知道，界面显示「?」。
     */
    @ColumnInfo(name = "last_known_coupons")
    public int lastKnownCoupons = -1;

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

    /**
     * 只有密码登录需要存密码；其余方式存了也用不上。
     *
     * <p>2026-08-23 起五种方式都能不用人插手切过去，所以「这种方式能不能自动」已经不再是个
     * 问题（原来的 {@code canAutoLogin()} 恒为 true，已删）。唯一还会让切号失败的前置条件就是
     * 这一条：密码登录却没存密码 —— 那种情况 {@link com.example.blb.auto.AccountSwitcher}
     * 会在<b>退登之前</b>就报错，不会把人退出来又登不回去。
     */
    public boolean needsPassword() {
        return KIND_PASSWORD.equals(loginKind);
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

    /** 余额一览，界面和日志共用。没读到过的那种券显示「?」，不编 0 出来。 */
    public String balanceText() {
        return "火券 " + (lastKnownCoupons >= 0 ? String.valueOf(lastKnownCoupons) : "?")
                + " / 代券 " + (lastKnownVouchers >= 0 ? String.valueOf(lastKnownVouchers) : "?");
    }

    /** 估算能付章节费的券总量：两种券都能付，代券先扣。 */
    public int usableCoupons() {
        return Math.max(0, lastKnownCoupons) + Math.max(0, lastKnownVouchers);
    }
}
