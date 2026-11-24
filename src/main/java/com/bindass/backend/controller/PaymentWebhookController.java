package com.bindass.backend.controller;

import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.service.OrderService;
import com.bindass.backend.service.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Razorpay → us. Public (no JWT) because Razorpay's servers call it, so the ONLY protection is the
 * HMAC signature in the X-Razorpay-Signature header, checked against the raw request bytes.
 *
 * Dashboard setup: Settings → Webhooks → URL https://&lt;your-api&gt;/api/payments/webhook,
 * secret = razorpay.webhook-secret, events = payment.captured, order.paid (payment.failed optional).
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final PaymentService paymentService;
    private final OrderService   orderService;
    private final ObjectMapper   objectMapper;

    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Void>> razorpayWebhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {

        // Fail closed: with no secret configured nothing can be authenticated.
        if (!paymentService.isWebhookConfigured()) {
            log.error("Razorpay webhook received but razorpay.webhook-secret is not configured — rejected");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Webhook is not configured"));
        }
        if (!paymentService.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Razorpay webhook with a missing/invalid signature rejected");
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid signature"));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Malformed payload"));
        }

        String event = root.path("event").asText("");
        switch (event) {
            // order.paid and payment.captured both mean "money is in" — handling is idempotent,
            // so receiving both (or a retry) is harmless.
            case "payment.captured", "order.paid" -> {
                JsonNode payment = root.path("payload").path("payment").path("entity");
                orderService.handleCapturedPayment(
                        payment.path("order_id").asText(null),
                        payment.path("id").asText(null),
                        payment.path("amount").asLong(-1),
                        payment.path("currency").asText(""));
            }
            case "payment.failed" -> {
                JsonNode payment = root.path("payload").path("payment").path("entity");
                log.info("Razorpay payment {} failed for order {} — order stays PENDING so the customer can retry",
                        payment.path("id").asText("?"), payment.path("order_id").asText("?"));
            }
            default -> log.debug("Razorpay webhook event '{}' ignored", event);
        }

        // 2xx = "got it, don't retry". Unexpected errors (DB down…) propagate as 5xx so Razorpay retries.
        return ResponseEntity.ok(ApiResponse.ok("Webhook processed"));
    }
}
