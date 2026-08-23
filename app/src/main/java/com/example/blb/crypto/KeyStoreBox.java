package com.example.blb.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 账号密码的本地加密。密钥由 AndroidKeyStore 生成并保存在其中，导不出来；
 * 数据库里只有密文和 IV。密码明文只在你填表的那一刻存在于内存。
 *
 * <p>密钥被系统作废（换锁屏、恢复出厂等）时 {@link #open} 会抛
 * {@link CryptoException}，上层应提示重新录入密码，而不是当成空密码继续。
 */
public final class KeyStoreBox {

    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "blb_cred_v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private KeyStoreBox() {
    }

    /** 密文 + IV，直接对应 Account.encPassword / Account.encIv。 */
    public static final class Sealed {
        public final byte[] cipherText;
        public final byte[] iv;

        Sealed(byte[] cipherText, byte[] iv) {
            this.cipherText = cipherText;
            this.iv = iv;
        }
    }

    public static class CryptoException extends Exception {
        CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Sealed seal(String plain) throws CryptoException {
        byte[] bytes = plain.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] out = cipher.doFinal(bytes);
            return new Sealed(out, cipher.getIV());
        } catch (Exception e) {
            throw new CryptoException("加密密码失败", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static String open(byte[] cipherText, byte[] iv) throws CryptoException {
        if (cipherText == null || iv == null) {
            throw new CryptoException("没有已保存的密码", null);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CryptoException("解密密码失败，可能需要重新录入", e);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return gen.generateKey();
    }
}
