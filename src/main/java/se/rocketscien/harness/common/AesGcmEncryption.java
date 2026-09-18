package se.rocketscien.harness.common;

import lombok.SneakyThrows;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class AesGcmEncryption {
    private static final String ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_SIZE = 12;
    private static final int TAG_LENGTH_BIT = 128;

    @SneakyThrows
    public static String encrypt(String plainText, byte[] key) {
        byte[] iv = new byte[IV_SIZE];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] result = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(cipherText, 0, result, iv.length, cipherText.length);

        return Base64.getEncoder().encodeToString(result);
    }

    @SneakyThrows
    public static String decrypt(String cipherTextBase64, byte[] key) {
        byte[] cipherTextWithIv = Base64.getDecoder().decode(cipherTextBase64);
        byte[] iv = new byte[IV_SIZE];
        byte[] cipherText = new byte[cipherTextWithIv.length - IV_SIZE];
        System.arraycopy(cipherTextWithIv, 0, iv, 0, IV_SIZE);
        System.arraycopy(cipherTextWithIv, IV_SIZE, cipherText, 0, cipherText.length);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        byte[] plainText = cipher.doFinal(cipherText);
        return new String(plainText, StandardCharsets.UTF_8);
    }
}
