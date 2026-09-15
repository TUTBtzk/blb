package com.example.blb.auto;

import static org.junit.Assert.*;

import com.example.blb.data.Account;
import org.junit.Test;

public class AccountSwitcherTest {
    private static final class LoginRunner extends OfflineRunner {
        LoginRunner() throws Exception { active = mine("账号甲"); }

        @Override public void clickNode(String label, NodeView node) throws StepFailure {
            super.clickNode(label, node);
            if (Keys.SETTINGS_ENTRY.equals(label)) {
                active = FakeNode.node().add(FakeNode.text("退出登录").withId("btnLoginOut"));
            } else if (Keys.LOGOUT_BUTTON.equals(label)) {
                active = FakeNode.node().add(FakeNode.text("立即登录").withId("toLoginBtn"));
            } else if (Keys.LOGIN_ENTRY.equals(label)) {
                active = FakeNode.node().add(FakeNode.text("一键登录/注册")
                        .withId("authsdk_login_view"));
            } else if (Keys.LOGIN_ONE_TAP.equals(label)) {
                active = mine("账号乙");
            }
        }

        private static NodeView mine(String name) {
            return FakeNode.node().add(FakeNode.node().withId("main_tab"),
                    FakeNode.node().withId("main_tab_container5"),
                    FakeNode.text(name).withId("nickname"),
                    FakeNode.text("设置").withId("my_setting_layout"));
        }
    }

    @Test public void soleEnabledWithoutNicknameMustCompleteItsOwnLogin() throws Exception {
        for (boolean soleEnabled : new boolean[]{true, false}) {
            LoginRunner runner = new LoginRunner();
            Account target = new Account();
            target.loginName = "账号乙的登录方式";
            target.loginKind = Account.KIND_PHONE_ONE_TAP;

            AccountSwitcher.ensureLoggedIn(runner, target, null, soleEnabled);

            assertTrue(runner.presses.contains(Keys.LOGOUT_BUTTON));
            assertTrue(runner.presses.contains(Keys.LOGIN_ONE_TAP));
            assertEquals("账号乙", target.nickname);
        }
    }

    @Test public void unknownNicknameWithNoPasswordFailsBeforeLoggingOut() throws Exception {
        LoginRunner runner = new LoginRunner();
        Account target = new Account();
        target.loginName = "账号乙";
        try {
            AccountSwitcher.ensureLoggedIn(runner, target, null, true);
            fail("只有一个启用账号也不能把当前账号甲认成账号乙");
        } catch (StepRunner.StepFailure failure) {
            assertEquals(StepRunner.Kind.LOGIN_FAILED, failure.kind);
        }
        assertNull(target.nickname);
        assertFalse(runner.presses.contains(Keys.LOGOUT_BUTTON));
    }

    @Test public void verifiedCurrentNicknameAvoidsUnnecessaryLogin() throws Exception {
        LoginRunner runner = new LoginRunner();
        Account target = new Account();
        target.nickname = "账号甲";
        AccountSwitcher.ensureLoggedIn(runner, target, null, false);
        assertFalse(runner.presses.contains(Keys.LOGOUT_BUTTON));
        assertEquals("账号甲", target.nickname);
    }
}
