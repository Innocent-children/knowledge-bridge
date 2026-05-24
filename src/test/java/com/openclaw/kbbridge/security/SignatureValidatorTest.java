package com.openclaw.kbbridge.security;

import com.openclaw.kbbridge.config.KbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SignatureValidator 单元测试。
 * 验证签名计算、签名验证和时间戳容差检查的正确性。
 */
class SignatureValidatorTest {

    private SignatureValidator validator;

    @BeforeEach
    void setUp() {
        KbProperties props = new KbProperties();
        props.getSecurity().setSharedSecret("test-shared-secret-key");
        props.getSecurity().setTimestampToleranceMs(300000); // 5 minutes
        props.getSecurity().setSignatureAlgorithm("HmacSHA256");
        validator = new SignatureValidator(props);
    }

    @Test
    void sign_returnsNonEmptyBase64String() {
        String signature = validator.sign("req-001", "1700000000000", "{\"question\":\"hello\"}");
        assertNotNull(signature);
        assertFalse(signature.isEmpty());
        // Verify it's valid Base64
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(signature));
    }

    @Test
    void sign_sameInputProducesSameSignature() {
        String sig1 = validator.sign("req-001", "1700000000000", "{\"question\":\"hello\"}");
        String sig2 = validator.sign("req-001", "1700000000000", "{\"question\":\"hello\"}");
        assertEquals(sig1, sig2);
    }

    @Test
    void sign_differentRequestIdProducesDifferentSignature() {
        String sig1 = validator.sign("req-001", "1700000000000", "{\"question\":\"hello\"}");
        String sig2 = validator.sign("req-002", "1700000000000", "{\"question\":\"hello\"}");
        assertNotEquals(sig1, sig2);
    }

    @Test
    void sign_differentTimestampProducesDifferentSignature() {
        String sig1 = validator.sign("req-001", "1700000000000", "{\"question\":\"hello\"}");
        String sig2 = validator.sign("req-001", "1700000000001", "{\"question\":\"hello\"}");
        assertNotEquals(sig1, sig2);
    }

    @Test
    void sign_differentBodyProducesDifferentSignature() {
        String sig1 = validator.sign("req-001", "1700000000000", "{\"question\":\"hello\"}");
        String sig2 = validator.sign("req-001", "1700000000000", "{\"question\":\"world\"}");
        assertNotEquals(sig1, sig2);
    }

    @Test
    void verify_validSignatureReturnsTrue() {
        String signature = validator.sign("req-001", "1700000000000", "body");
        assertTrue(validator.verify("req-001", "1700000000000", "body", signature));
    }

    @Test
    void verify_tamperedSignatureReturnsFalse() {
        String signature = validator.sign("req-001", "1700000000000", "body");
        assertFalse(validator.verify("req-001", "1700000000000", "body", signature + "x"));
    }

    @Test
    void verify_differentRequestIdReturnsFalse() {
        String signature = validator.sign("req-001", "1700000000000", "body");
        assertFalse(validator.verify("req-002", "1700000000000", "body", signature));
    }

    @Test
    void verify_differentBodyReturnsFalse() {
        String signature = validator.sign("req-001", "1700000000000", "body");
        assertFalse(validator.verify("req-001", "1700000000000", "tampered-body", signature));
    }

    @Test
    void verify_differentSecretReturnsFalse() {
        String signature = validator.sign("req-001", "1700000000000", "body");

        // Create a validator with a different secret
        KbProperties otherProps = new KbProperties();
        otherProps.getSecurity().setSharedSecret("different-secret");
        otherProps.getSecurity().setSignatureAlgorithm("HmacSHA256");
        SignatureValidator otherValidator = new SignatureValidator(otherProps);

        assertFalse(otherValidator.verify("req-001", "1700000000000", "body", signature));
    }

    @Test
    void verify_emptyBodyWorks() {
        String signature = validator.sign("req-001", "1700000000000", "");
        assertTrue(validator.verify("req-001", "1700000000000", "", signature));
    }

    @Test
    void isTimestampValid_currentTimestampReturnsTrue() {
        String now = String.valueOf(System.currentTimeMillis());
        assertTrue(validator.isTimestampValid(now));
    }

    @Test
    void isTimestampValid_withinToleranceReturnsTrue() {
        // 4 minutes ago — within 5 minute tolerance
        long fourMinutesAgo = System.currentTimeMillis() - 240000;
        assertTrue(validator.isTimestampValid(String.valueOf(fourMinutesAgo)));
    }

    @Test
    void isTimestampValid_exceedsToleranceReturnsFalse() {
        // 6 minutes ago — exceeds 5 minute tolerance
        long sixMinutesAgo = System.currentTimeMillis() - 360000;
        assertFalse(validator.isTimestampValid(String.valueOf(sixMinutesAgo)));
    }

    @Test
    void isTimestampValid_futureWithinToleranceReturnsTrue() {
        // 4 minutes in the future — within tolerance
        long fourMinutesFuture = System.currentTimeMillis() + 240000;
        assertTrue(validator.isTimestampValid(String.valueOf(fourMinutesFuture)));
    }

    @Test
    void isTimestampValid_futureExceedsToleranceReturnsFalse() {
        // 6 minutes in the future — exceeds tolerance
        long sixMinutesFuture = System.currentTimeMillis() + 360000;
        assertFalse(validator.isTimestampValid(String.valueOf(sixMinutesFuture)));
    }

    @Test
    void isTimestampValid_invalidFormatReturnsFalse() {
        assertFalse(validator.isTimestampValid("not-a-number"));
    }

    @Test
    void isTimestampValid_emptyStringReturnsFalse() {
        assertFalse(validator.isTimestampValid(""));
    }

    @Test
    void isTimestampValid_customToleranceIsRespected() {
        // Create validator with 1 second tolerance
        KbProperties props = new KbProperties();
        props.getSecurity().setSharedSecret("test-secret");
        props.getSecurity().setTimestampToleranceMs(1000);
        props.getSecurity().setSignatureAlgorithm("HmacSHA256");
        SignatureValidator strictValidator = new SignatureValidator(props);

        // Current time should pass
        assertTrue(strictValidator.isTimestampValid(String.valueOf(System.currentTimeMillis())));

        // 2 seconds ago should fail with 1s tolerance
        long twoSecondsAgo = System.currentTimeMillis() - 2000;
        assertFalse(strictValidator.isTimestampValid(String.valueOf(twoSecondsAgo)));
    }
}
