package com.openclaw.kbbridge.security;

import com.openclaw.kbbridge.config.KbProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * HMAC-SHA256 签名验证器。
 * <p>
 * 负责计算和验证请求签名，确保只有持有共享密钥的调用方（OpenClaw）能访问 Knowledge Bridge 接口。
 * 签名内容：requestId + timestamp + SHA256(requestBody)，使用 HMAC-SHA256 算法计算。
 * </p>
 */
@Component
public class SignatureValidator {

    private final KbProperties.Security securityConfig;

    public SignatureValidator(KbProperties kbProperties) {
        this.securityConfig = kbProperties.getSecurity();
    }

    /**
     * 计算请求签名。
     * <p>
     * 签名流程：
     * 1. 对 requestBody 计算 SHA-256 摘要（十六进制）
     * 2. 拼接签名内容：requestId + timestamp + bodyHash
     * 3. 使用共享密钥计算 HMAC-SHA256
     * 4. 返回 Base64 编码的签名字符串
     * </p>
     *
     * @param requestId   请求唯一标识
     * @param timestamp   Unix 毫秒时间戳字符串
     * @param requestBody 请求体内容
     * @return Base64 编码的 HMAC-SHA256 签名
     */
    public String sign(String requestId, String timestamp, String requestBody) {
        try {
            String bodyHash = sha256Hex(requestBody);
            String signatureContent = requestId + timestamp + bodyHash;

            Mac mac = Mac.getInstance(securityConfig.getSignatureAlgorithm());
            SecretKeySpec keySpec = new SecretKeySpec(
                    securityConfig.getSharedSecret().getBytes(StandardCharsets.UTF_8),
                    securityConfig.getSignatureAlgorithm());
            mac.init(keySpec);

            byte[] hmacBytes = mac.doFinal(signatureContent.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /**
     * 验证请求签名是否合法。
     * <p>
     * 使用相同的签名流程重新计算签名，并与请求头中的签名值进行常量时间比对，防止时序攻击。
     * </p>
     *
     * @param requestId   请求唯一标识
     * @param timestamp   Unix 毫秒时间戳字符串
     * @param requestBody 请求体内容
     * @param signature   请求头中的 Base64 编码签名值
     * @return 签名是否合法
     */
    public boolean verify(String requestId, String timestamp, String requestBody, String signature) {
        String expected = sign(requestId, timestamp, requestBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 检查请求时间戳是否在容差范围内。
     * <p>
     * 判断 |当前时间 - 请求时间戳| 是否不超过配置的容差值（默认 5 分钟）。
     * </p>
     *
     * @param timestamp Unix 毫秒时间戳字符串
     * @return 时间戳是否有效
     */
    public boolean isTimestampValid(String timestamp) {
        try {
            long requestTime = Long.parseLong(timestamp);
            long currentTime = System.currentTimeMillis();
            long diff = Math.abs(currentTime - requestTime);
            return diff <= securityConfig.getTimestampToleranceMs();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 计算字符串的 SHA-256 摘要并返回十六进制字符串。
     */
    private String sha256Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
