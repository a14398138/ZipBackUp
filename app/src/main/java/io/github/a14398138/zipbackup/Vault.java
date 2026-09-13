package io.github.a14398138.zipbackup;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

final class Vault {
    private static final String ALIAS = "zipbackup-password-v1";
    private static javax.crypto.SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            gen.generateKey();
        }
        return (javax.crypto.SecretKey) ks.getKey(ALIAS, null);
    }
    static void save(Context c, char[] password) throws Exception {
        if (!new Settings(c).prefs.edit().putString("password", encrypt(password)).commit()) throw new java.io.IOException("パスワードを保存できません");
    }
    static String encrypt(char[] password) throws Exception {
        byte[] plain = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP);
            return value;
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    static char[] read(Context c) throws Exception {
        return decrypt(new Settings(c).prefs.getString("password", ""));
    }
    static char[] decrypt(String value) throws Exception {
        if (value.isEmpty()) throw new IllegalStateException("暗号化パスワードを設定してください");
        String[] parts = value.split(":");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        byte[] plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
        try { return new String(plain, StandardCharsets.UTF_8).toCharArray(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
}
