package com.bindass.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Signature checks: the browser-side payment signature and the Razorpay webhook signature. */
class PaymentServiceTest {

    static final String KEY_SECRET = "key_secret_for_tests";
    static final String WEBHOOK_SECRET = "webhook_secret_for_tests";

    PaymentService svc;
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void setUp() {
        svc = new PaymentService();
        ReflectionTestUtils.setField(svc, "keyId", "rzp_test_x");
        ReflectionTestUtils.setField(svc, "keySecret", KEY_SECRET);
        ReflectionTestUtils.setField(svc, "webhookSecret", WEBHOOK_SECRET);

        logger = (Logger) LoggerFactory.getLogger(PaymentService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    static String hmac(String secret, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data));
    }

    static String paymentSig(String orderId, String paymentId) throws Exception {
        return hmac(KEY_SECRET, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    // ── browser-side payment signature ──────────────────────

    @Test
    void validPaymentSignature_isAccepted_caseInsensitively() throws Exception {
        String sig = paymentSig("order_1", "pay_1");
        assertTrue(svc.verifySignature("order_1", "pay_1", sig));
        assertTrue(svc.verifySignature("order_1", "pay_1", sig.toUpperCase()));
        assertTrue(svc.verifySignature(" order_1 ", " pay_1 ", " " + sig + " "));
    }

    @Test
    void wrongOrMissingPaymentSignature_isRejected() throws Exception {
        String sig = paymentSig("order_1", "pay_1");
        assertFalse(svc.verifySignature("order_1", "pay_2", sig));          // different payment
        assertFalse(svc.verifySignature("order_2", "pay_1", sig));          // different order
        assertFalse(svc.verifySignature("order_1", "pay_1", "deadbeef"));
        assertFalse(svc.verifySignature("order_1", "pay_1", sig.substring(0, 30)));
        assertFalse(svc.verifySignature("order_1", "pay_1", ""));
        assertFalse(svc.verifySignature(null, "pay_1", sig));
        assertFalse(svc.verifySignature("order_1", null, sig));
        assertFalse(svc.verifySignature("order_1", "pay_1", null));
    }

    @Test
    void mismatchLog_neverRevealsTheExpectedSignature() throws Exception {
        // Regression: the old code logged "Expected: <valid signature>" on every mismatch,
        // handing anyone with log access a working forgery for that order|payment pair.
        String valid = paymentSig("order_1", "pay_1");
        svc.verifySignature("order_1", "pay_1", "forged_" + "0".repeat(57));

        assertFalse(logs.list.isEmpty(), "a mismatch should still be logged");
        for (ILoggingEvent e : logs.list) {
            String text = e.getFormattedMessage();
            assertFalse(text.toLowerCase().contains(valid.toLowerCase()), "log leaked the expected signature: " + text);
            assertFalse(text.contains("forged_"), "log echoed the attacker-supplied signature: " + text);
        }
    }

    // ── webhook signature ───────────────────────────────────

    @Test
    void validWebhookSignature_isAccepted() throws Exception {
        byte[] body = "{\"event\":\"payment.captured\",\"note\":\"₹ ünïcode\"}".getBytes(StandardCharsets.UTF_8);
        assertTrue(svc.isWebhookConfigured());
        assertTrue(svc.verifyWebhookSignature(body, hmac(WEBHOOK_SECRET, body)));
        assertTrue(svc.verifyWebhookSignature(body, hmac(WEBHOOK_SECRET, body).toUpperCase()));
    }

    @Test
    void tamperedOrForgedWebhook_isRejected() throws Exception {
        byte[] body = "{\"event\":\"payment.captured\",\"amount\":100}".getBytes(StandardCharsets.UTF_8);
        String good = hmac(WEBHOOK_SECRET, body);

        byte[] tampered = "{\"event\":\"payment.captured\",\"amount\":999}".getBytes(StandardCharsets.UTF_8);
        assertFalse(svc.verifyWebhookSignature(tampered, good), "body changed after signing");
        assertFalse(svc.verifyWebhookSignature(body, hmac("some_other_secret", body)), "signed with another secret");
        // the API key secret must NOT be accepted as a webhook secret
        assertFalse(svc.verifyWebhookSignature(body, hmac(KEY_SECRET, body)));
        assertFalse(svc.verifyWebhookSignature(body, "deadbeef"));
        assertFalse(svc.verifyWebhookSignature(body, ""));
        assertFalse(svc.verifyWebhookSignature(body, "   "));
        assertFalse(svc.verifyWebhookSignature(body, null));
        assertFalse(svc.verifyWebhookSignature(null, good));
    }

    @Test
    void webhook_failsClosedWhenNoSecretIsConfigured() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        for (String unset : new String[]{null, "", "   "}) {
            ReflectionTestUtils.setField(svc, "webhookSecret", unset);
            assertFalse(svc.isWebhookConfigured());
            // even a signature made with an empty key must not get through
            assertFalse(svc.verifyWebhookSignature(body, "anything"));
            assertFalse(svc.verifyWebhookSignature(body, hmac("x", body)));
        }
    }
}
