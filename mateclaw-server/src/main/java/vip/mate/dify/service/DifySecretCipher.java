package vip.mate.dify.service;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class DifySecretCipher {

    @Value("${mateclaw.datasource.encrypt-key:MateClaw@2024Key!}")
    private String encryptKey;

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        return aes().encryptHex(plaintext.trim());
    }

    public String decrypt(String ciphertext, Long configId) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new MateClawException("err.dify.api_key_required", 400,
                    "Dify API key is not configured");
        }
        try {
            return aes().decryptStr(ciphertext);
        } catch (Exception e) {
            throw new MateClawException("err.dify.api_key_decrypt_failed", 500,
                    "Failed to decrypt Dify API key for config " + configId);
        }
    }

    private AES aes() {
        byte[] keyBytes = Arrays.copyOf(encryptKey.getBytes(StandardCharsets.UTF_8), 16);
        return SecureUtil.aes(keyBytes);
    }
}
