package com.bindass.backend.service;

import com.razorpay.*;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

@Service
@Slf4j
public class PaymentService {

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    /** Secret you type into Razorpay Dashboard → Webhooks. NOT the API key secret. */
    @Value("${razorpay.webhook-secret:}")
    private String webhookSecret;

    public String getKeyId() {
        return keyId != null ? keyId.trim() : "";
    }

    public String getKeySecret() {
        return keySecret != null ? keySecret.trim() : "";
    }

    public boolean isWebhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public String createRazorpayOrder(double amountInRupees, String receipt)
            throws RazorpayException {

        RazorpayClient client = new RazorpayClient(getKeyId(), getKeySecret());

        JSONObject options = new JSONObject();
        options.put("amount",   (int) Math.round(amountInRupees * 100));
        options.put("currency", "INR");
        options.put("receipt",  receipt);
        options.put("notes",    new JSONObject().put("brand", "BINDASS"));

        com.razorpay.Order order = client.orders.create(options);
        String orderId = order.get("id").toString();
        log.info("Razorpay order created: {}", orderId);

        return orderId;
    }

    /** Checks the signature the Razorpay popup hands to the browser: HMAC(orderId|paymentId). */
    public boolean verifySignature(
            String razorpayOrderId,
            String razorpayPaymentId,
            String razorpaySignature
    ) {
        if (razorpayOrderId == null || razorpayPaymentId == null || razorpaySignature == null) {
            log.error("Missing signature verification parameters");
            return false;
        }

        try {
            String payload = razorpayOrderId.trim() + "|" + razorpayPaymentId.trim();
            String expected = hmacSha256Hex(payload.getBytes(StandardCharsets.UTF_8), getKeySecret());
            boolean matches = constantTimeEquals(expected, razorpaySignature.trim());
            if (!matches) {
                // never log the expected value: it IS the valid signature for this payment
                log.warn("Payment signature mismatch for Razorpay order {}", razorpayOrderId.trim());
            }
            return matches;

        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks a Razorpay webhook: the X-Razorpay-Signature header must equal
     * HMAC-SHA256(raw request body, webhook secret). The RAW bytes are required —
     * re-serialising parsed JSON would change them and break the signature.
     * Returns false whenever no webhook secret is configured (never accept unsigned calls).
     */
    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        if (!isWebhookConfigured() || rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            String expected = hmacSha256Hex(rawBody, webhookSecret.trim());
            return constantTimeEquals(expected, signature.trim());
        } catch (Exception e) {
            log.error("Webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    public void initiateRefund(String paymentId, double amountInRupees)
            throws RazorpayException {

        RazorpayClient client = new RazorpayClient(getKeyId(), getKeySecret());

        JSONObject refundRequest = new JSONObject();
        refundRequest.put("amount", (int) Math.round(amountInRupees * 100));
        refundRequest.put("notes",  new JSONObject()
                .put("reason", "Customer cancellation")
                .put("brand",  "BINDASS"));

        Refund refund = client.payments.refund(paymentId, refundRequest);
        String refundId = refund.get("id").toString();
        log.info("Refund initiated: {}", refundId);
    }

    // ── helpers ──────────────────────────────────────────────

    private static String hmacSha256Hex(byte[] data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data));
    }

    /** Constant-time, case-insensitive hex comparison (no early exit on the first wrong byte). */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                b.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
