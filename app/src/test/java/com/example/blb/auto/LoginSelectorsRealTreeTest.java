package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * 把 2026-08-23 逐页 dump 到的登录／退登界面钉在这儿。
 *
 * <p>这一批选择器决定「能不能自动切号」。用户手指动不了，一颗键认错就等于把他锁在登录页上，
 * 所以每一条实测结论都在这里留一个用例 —— 尤其是那几条<b>不该命中</b>的：
 * 登录入口不许认密码页那颗 text=登录 的提交键、提交键不许认「立即登录」、
 * 授权确认不许认「取消／切换账号／不同意」。
 */
public class LoginSelectorsRealTreeTest {

    private static Map<String, List<Selector>> bundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json", f.isFile());
        return SelectorSet.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    private static NodeView hit(Map<String, List<Selector>> m, String key, FakeNode root) {
        NodeMatcher.Hit h = NodeMatcher.find(root, m.get(key));
        return h == null ? null : h.node;
    }

    private static String idOf(NodeView node) {
        assertNotNull("没命中任何节点", node);
        String id = node.viewId();
        if (id == null) return null;
        int slash = id.indexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    // ---------- 实测界面 ----------

    /**
     * 一键登录页 com.mobile.auth.gatewayauth.LoginAuthActivity（阿里云号码认证，属于 com.sfacg
     * 进程，所以本来的无障碍范围就读得到）。三个三方图标实测<b>既没有 text 也没有 desc</b>，
     * 这里照原样不给它们文字 —— 旧那套按 desc=「微信」匹配的写法就是这样在真机上全军覆没的。
     */
    private static FakeNode oneTapPage() {
        FakeNode innerCheckBox = FakeNode.node()
                .withClass("android.widget.CheckBox").checked(false)
                .withBounds(90, 2207, 159, 2276);
        FakeNode checkboxWrap = FakeNode.node()
                .withId("com.sfacg:id/authsdk_checkbox_view")
                .withClass("android.widget.FrameLayout").clickable(true)
                .withBounds(77, 2207, 173, 2276)
                .add(innerCheckBox);
        FakeNode bigButton = FakeNode.node()
                .withId("com.sfacg:id/authsdk_login_view")
                .withClass("android.widget.RelativeLayout").clickable(true)
                .withBounds(112, 757, 967, 886)
                .add(FakeNode.text("一键登录/注册").withClass("android.widget.TextView"));
        return FakeNode.node().withClass("android.widget.FrameLayout").add(
                FakeNode.text("195****3032").withId("com.sfacg:id/authsdk_number_view"),
                bigButton,
                FakeNode.text("切换手机号或邮箱登录")
                        .withId("com.sfacg:id/authsdk_switch_view").clickable(true)
                        .withBounds(350, 963, 730, 1015),
                checkboxWrap,
                FakeNode.text("我已阅读并同意《用户协议》和《隐私政策》"),
                FakeNode.node().withId("com.sfacg:id/wxBtnLayout").clickable(true)
                        .withBounds(164, 2023, 396, 2140),
                FakeNode.node().withId("com.sfacg:id/qqBtnLayout").clickable(true)
                        .withBounds(424, 2023, 656, 2140),
                FakeNode.node().withId("com.sfacg:id/sinaBtnLayout").clickable(true)
                        .withBounds(684, 2023, 916, 2140));
    }

    /**
     * 密码登录页 com.sf.login.LoginActivity。实测 check_confirm_protocol 的 <b>checked 就是
     * true</b> —— 按下「切换手机号或邮箱登录」的那一刻协议已经自动同意，再点一下就是取消。
     */
    private static FakeNode passwordPage() {
        return FakeNode.node().withClass("android.widget.LinearLayout").add(
                FakeNode.node().withId("com.sfacg:id/back_img").clickable(true),
                FakeNode.node().withId("com.sfacg:id/etAccount")
                        .withClass("android.widget.EditText").withBounds(95, 700, 984, 780),
                FakeNode.node().withId("com.sfacg:id/etPassword")
                        .withClass("android.widget.EditText").withBounds(95, 820, 984, 900),
                FakeNode.node().withId("com.sfacg:id/check_confirm_protocol")
                        .withClass("android.widget.CheckBox").checked(true)
                        .withBounds(95, 1200, 150, 1250),
                FakeNode.text("已阅读并同意《用户协议》和《隐私政策》"),
                FakeNode.text("登录").withId("com.sfacg:id/btn_login")
                        .withClass("android.widget.Button").clickable(true)
                        .withBounds(95, 1004, 984, 1141),
                FakeNode.node().withId("com.sfacg:id/wxLoginBtn").clickable(true));
    }

    /** 未登录的「我的」页：滚到顶部之后才看得见 toLoginBtn。 */
    private static FakeNode minePageLoggedOut() {
        return FakeNode.node().withClass("android.widget.ScrollView").scrollable(true).add(
                FakeNode.text("立即登录").withId("com.sfacg:id/toLoginBtn").clickable(true)
                        .withBounds(307, 528, 773, 662),
                FakeNode.node().withId("com.sfacg:id/my_setting_layout").clickable(true)
                        .withBounds(77, 2000, 1000, 2100)
                        .add(FakeNode.text("设置").withId("com.sfacg:id/setting_title")
                                .withBounds(134, 2021, 214, 2075)));
    }

    /** 设置页 com.sf.view.activity.SystemSettingActivity，往下翻 4 次才看得见退出登录。 */
    private static FakeNode settingsPage() {
        return FakeNode.node().withClass("android.widget.ScrollView").scrollable(true).add(
                FakeNode.node().withId("com.sfacg:id/lltLoginOut").clickable(true)
                        .withBounds(0, 2000, 1080, 2120)
                        .add(FakeNode.text("退出登录").withId("com.sfacg:id/btnLoginOut")));
    }

    /**
     * QQ 的授权页 com.tencent.mobileqq/com.tencent.open.agent.PublicFragmentActivityForOpenSDK。
     * 只有这一页允许在 com.sfacg 之外匹配，而且只许匹配 login_auth_confirm 这一组。
     *
     * <p>文案照 2026-08-23 15:18 的实测截图补全：页面上还有一颗可点的「创建」（新建头像昵称）
     * 和两行昵称（「皓平」「阿娴」），底部才是那颗「同意」。这几颗一个都不许被当成授权键 ——
     * 点「创建」会去建新马甲，点昵称行只是换用哪个头像登录。
     */
    private static FakeNode qqAuthPage() {
        return FakeNode.node().withClass("android.widget.FrameLayout").add(
                FakeNode.text("SF互动传媒网 申请使用").withId("com.tencent.mobileqq:id/vu"),
                FakeNode.text("你的QQ头像和昵称信息"),
                FakeNode.text("取消").withId("com.tencent.mobileqq:id/ivTitleBtnRightText")
                        .clickable(true),
                FakeNode.text("你可以使用不同的头像/昵称登录"),
                FakeNode.text("创建").clickable(true),
                FakeNode.text("皓平").clickable(true),
                FakeNode.text("QQ头像昵称"),
                FakeNode.text("阿娴").clickable(true),
                FakeNode.text("切换账号").withId("com.tencent.mobileqq:id/asz").clickable(true),
                FakeNode.text("同意").withDesc("同意").withId("com.tencent.mobileqq:id/fds")
                        .withClass("android.widget.Button").clickable(true)
                        .withBounds(313, 1960, 767, 2082));
    }

    // ---------- 一键登录页 ----------

    @Test
    public void oneTapPage_hitsButtonSwitchAndThreeIcons() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode page = oneTapPage();
        assertEquals("authsdk_login_view", idOf(hit(m, Keys.LOGIN_ONE_TAP, page)));
        assertEquals("authsdk_switch_view", idOf(hit(m, Keys.LOGIN_SWITCH_PHONE, page)));
        assertEquals("wxBtnLayout", idOf(hit(m, Keys.LOGIN_WECHAT, page)));
        assertEquals("qqBtnLayout", idOf(hit(m, Keys.LOGIN_QQ, page)));
        assertEquals("sinaBtnLayout", idOf(hit(m, Keys.LOGIN_WEIBO, page)));
    }

    /**
     * 协议勾选框必须命中<b>里面那颗读得出状态的 CheckBox</b>，而不是外层那个 FrameLayout。
     * 外层的 checked 恒为 false，先命中它的话，在协议已经勾上时 agree() 会再点一次、把勾取消掉，
     * 而日志里只表现成「按了登录没反应」。
     */
    @Test
    public void oneTapPage_agreeHitsTheNodeThatReportsCheckedState() throws Exception {
        NodeView agree = hit(bundled(), Keys.LOGIN_AGREE_CHECKBOX, oneTapPage());
        assertNotNull("一键登录页上没认出协议勾选框", agree);
        assertTrue("命中的应该是里面那颗 CheckBox，不是外层 FrameLayout",
                agree.className().contains("CheckBox"));
        assertFalse("这一页的协议实测是没勾上的，agree() 该点它一下", agree.checked());
    }

    @Test
    public void oneTapPage_hasNoLoginEntryAndNoSubmit() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode page = oneTapPage();
        assertNull("一键登录页上不该认出「登录入口」", hit(m, Keys.LOGIN_ENTRY, page));
        assertNull("「一键登录/注册」不是密码表单的提交键", hit(m, Keys.LOGIN_SUBMIT, page));
    }

    // ---------- 密码登录页 ----------

    @Test
    public void passwordPage_hitsFieldsAndSubmit() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode page = passwordPage();
        assertEquals("etAccount", idOf(hit(m, Keys.LOGIN_ACCOUNT_FIELD, page)));
        assertEquals("etPassword", idOf(hit(m, Keys.LOGIN_PASSWORD_FIELD, page)));
        assertEquals("btn_login", idOf(hit(m, Keys.LOGIN_SUBMIT, page)));
    }

    /** 这一页的协议实测已经勾上了，所以 agree() 必须什么都不做。 */
    @Test
    public void passwordPage_agreeIsAlreadyChecked() throws Exception {
        NodeView agree = hit(bundled(), Keys.LOGIN_AGREE_CHECKBOX, passwordPage());
        assertEquals("check_confirm_protocol", idOf(agree));
        assertTrue("实测密码页的协议默认就是勾上的，再点一下会取消", agree.checked());
    }

    /** btn_login 的 text 正好是「登录」两个字，登录入口那一组绝不能认它。 */
    @Test
    public void passwordPage_loginEntryDoesNotHitSubmitButton() throws Exception {
        assertNull("在密码页把提交键当成「登录入口」会原地反复点",
                hit(bundled(), Keys.LOGIN_ENTRY, passwordPage()));
    }

    // ---------- 未登录的「我的」页与设置页 ----------

    @Test
    public void minePage_hitsLoginEntryAndSettings() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode page = minePageLoggedOut();
        assertEquals("toLoginBtn", idOf(hit(m, Keys.LOGIN_ENTRY, page)));
        assertEquals("my_setting_layout", idOf(hit(m, Keys.SETTINGS_ENTRY, page)));
        assertNull("「立即登录」是入口，不是密码表单的提交键",
                hit(m, Keys.LOGIN_SUBMIT, page));
    }

    /**
     * 实测退出登录没有二次确认框，所以 logout_confirm 认不到是正常的，不许因此报错。
     *
     * <p>命中的是外层 {@code lltLoginOut} 而不是写着「退出登录」的 {@code btnLoginOut}：
     * 文字节点自己不可点，{@code clickableAncestor} 往上走一层拿到了那个可点的容器。
     */
    @Test
    public void settingsPage_hitsLogoutAndHasNoConfirmDialog() throws Exception {
        Map<String, List<Selector>> m = bundled();
        FakeNode page = settingsPage();
        assertEquals("lltLoginOut", idOf(hit(m, Keys.LOGOUT_BUTTON, page)));
        assertNull("设置页上本来就没有确认框", hit(m, Keys.LOGOUT_CONFIRM, page));
    }

    // ---------- QQ 授权页：唯一允许在 com.sfacg 之外匹配的一组 ----------

    @Test
    public void qqAuthPage_hitsAgreeButton() throws Exception {
        NodeView agree = hit(bundled(), Keys.LOGIN_AUTH_CONFIRM, qqAuthPage());
        assertNotNull("QQ 授权页上没认出那颗「同意」", agree);
        assertEquals("同意", agree.text());
        assertEquals("fds", idOf(agree));
    }

    /** 那一组只认明确的授权键：取消／切换账号／注册／下载一律不许命中。 */
    @Test
    public void authConfirm_ignoresCancelAndSwitchAccount() throws Exception {
        FakeNode page = FakeNode.node().add(
                FakeNode.text("SF互动传媒网 申请使用"),
                FakeNode.text("取消").clickable(true),
                FakeNode.text("切换账号").clickable(true),
                FakeNode.text("注册新账号").clickable(true),
                FakeNode.text("下载并安装").clickable(true));
        assertNull("在别人的 App 里按错键是这一块最不能犯的错",
                hit(bundled(), Keys.LOGIN_AUTH_CONFIRM, page));
    }

    /**
     * 授权页上那颗「创建」（新建头像昵称）和两行昵称都排在「同意」前面 —— DFS 先撞上它们。
     * 命中的必须还是底部那颗 Button：点「创建」会去建新马甲，点昵称行只是换个头像登录，
     * 两下都换不来登录成功，而人在旁边也按不动屏幕来收拾。
     */
    @Test
    public void authConfirm_ignoresCreateAndNicknameRows() throws Exception {
        NodeView agree = hit(bundled(), Keys.LOGIN_AUTH_CONFIRM, qqAuthPage());
        assertEquals("fds", idOf(agree));
        for (String other : new String[]{"创建", "皓平", "阿娴", "你可以使用不同的头像/昵称登录"}) {
            FakeNode page = FakeNode.node().add(FakeNode.text(other).clickable(true));
            assertNull(other + " 不是授权键", hit(bundled(), Keys.LOGIN_AUTH_CONFIRM, page));
        }
    }

    /** desc 那一条刻意用完全相等：用「包含」的话「不同意」也会命中。 */
    @Test
    public void authConfirm_neverHitsDisagree() throws Exception {
        FakeNode page = FakeNode.node().add(
                FakeNode.text("不同意").withDesc("不同意").withClass("android.widget.Button")
                        .clickable(true),
                FakeNode.text("同意并继续下载").withClass("android.widget.Button").clickable(true));
        assertNull("「不同意」「同意并继续下载」都不是授权键",
                hit(bundled(), Keys.LOGIN_AUTH_CONFIRM, page));
    }

}
