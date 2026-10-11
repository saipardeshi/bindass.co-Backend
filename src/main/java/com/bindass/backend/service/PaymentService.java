package com.bindass.backend.service;

import com.razorpay.*;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Service
@Slf4j
public class PaymentService {

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    public String getKeyId() {
        return keyId != null ? keyId.trim() : "";
    }

    public String getKeySecret() {
        return keySecret != null ? keySecret.trim() : "";
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

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    getKeySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            );
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            String expected = HexFormat.of().formatHex(hash);
            boolean matches = expected.equalsIgnoreCase(razorpaySignature.trim());
            if (!matches) {
                log.warn("Signature mismatch. Expected: {}, Received: {}", expected, razorpaySignature);
            }
            return matches;

        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage(), e);
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
}