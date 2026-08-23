package com.example.blb.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 密码加解密往返。必须跑在真机/模拟器上，AndroidKeyStore 在普通 JVM 里不存在。 */
@RunWith(AndroidJUnit4.class)
public class KeyStoreBoxTest {

    @Test
    public void sealThenOpenGivesBackTheSamePassword() throws Exception {
        String plain = "P@ssw0rd-中文-🙂";
        KeyStoreBox.Sealed sealed = KeyStoreBox.seal(plain);

        assertNotNull(sealed.cipherText);
        assertNotNull(sealed.iv);
        assertTrue(sealed.cipherText.length > 0);
        assertEquals("GCM 的 IV 应当是 12 字节", 12, sealed.iv.length);
        assertEquals(plain, KeyStoreBox.open(sealed.cipherText, sealed.iv));
    }

    @Test
    public void cipherTextDoesNotContainThePlainText() throws Exception {
        String plain = "hunter2hunter2";
        KeyStoreBox.Sealed sealed = KeyStoreBox.seal(plain);
        String asLatin = new String(sealed.cipherText, StandardCharsets.ISO_8859_1);
        assertFalse(asLatin.contains(plain));
    }

    @Test
    public void sameInputSealsDifferentlyEveryTime() throws Exception {
        KeyStoreBox.Sealed a = KeyStoreBox.seal("same");
        KeyStoreBox.Sealed b = KeyStoreBox.seal("same");
        assertFalse("IV 必须随机，不然同密码密文相同", Arrays.equals(a.iv, b.iv));
        assertNotEquals(Arrays.toString(a.cipherText), Arrays.toString(b.cipherText));
        assertEquals("same", KeyStoreBox.open(a.cipherText, a.iv));
        assertEquals("same", KeyStoreBox.open(b.cipherText, b.iv));
    }

    @Test
    public void emptyPasswordStillRoundTrips() throws Exception {
        KeyStoreBox.Sealed sealed = KeyStoreBox.seal("");
        assertEquals("", KeyStoreBox.open(sealed.cipherText, sealed.iv));
    }

    @Test
    public void missingCipherTextIsReportedNotTreatedAsEmpty() {
        try {
            KeyStoreBox.open(null, null);
            fail("没有密码时不能静默返回空串");
        } catch (KeyStoreBox.CryptoException expected) {
            // 正是想要的：上层会提示重新录入
        }
    }

    @Test
    public void tamperedCipherTextIsRejectedByTheGcmTag() throws Exception {
        KeyStoreBox.Sealed sealed = KeyStoreBox.seal("original");
        byte[] tampered = sealed.cipherText.clone();
        tampered[0] ^= 0x01;
        try {
            KeyStoreBox.open(tampered, sealed.iv);
            fail("改过的密文竟然解开了");
        } catch (KeyStoreBox.CryptoException expected) {
            // 正是想要的
        }
    }
}
