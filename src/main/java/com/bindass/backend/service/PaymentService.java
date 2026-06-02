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

    public String createRazorpayOrder(double amountInRupees, String receipt)
            throws RazorpayException {

        RazorpayClient client = new RazorpayClient(keyId, keySecret);

        JSONObject options = new JSONObject();
        options.put("amount",   (int)(amountInRupees * 100));
        options.put("currency", "INR");
        options.put("receipt",  receipt);
        options.put("notes",    new JSONObject().put("brand", "BINDASS"));

        com.razorpay.Order order = client.orders.create(options);
        log.info("Razorpay order created: " + order.get("id").toString());

        return order.get("id").toString();
    }

    public boolean verifySignature(
            String razorpayOrderId,
            String razorpayPaymentId,
            String razorpaySignature
    ) {
        try {
            String payload = razorpayOrderId + "|" + razorpayPaymentId;

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            );
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            String expected = HexFormat.of().formatHex(hash);
            return expected.equals(razorpaySignature);

        } catch (Exception e) {
            log.error("Signature verification failed: " + e.getMessage());
            return false;
        }
    }

    public void initiateRefund(String paymentId, double amountInRupees)
            throws RazorpayException {

        RazorpayClient client = new RazorpayClient(keyId, keySecret);

        JSONObject refundRequest = new JSONObject();
        refundRequest.put("amount", (int)(amountInRupees * 100));
        refundRequest.put("notes",  new JSONObject()
                .put("reason", "Customer cancellation")
                .put("brand",  "BINDASS"));

        Refund refund = client.payments.refund(paymentId, refundRequest);
        log.info("Refund initiated: " + refund.get("id").toString());
    }
}