package com.bindass.backend.controller;

import com.bindass.backend.exception.GlobalExceptionHandler;
import com.bindass.backend.service.OrderService;
import com.bindass.backend.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the webhook endpoint with the REAL signature check (only OrderService is mocked),
 * so the raw-body handling is covered, not just the happy path.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    static final String SECRET = "webhook_secret_for_tests";
    static final String URL = "/api/payments/webhook";

    @Mock OrderService orderService;
    PaymentService payments;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        payments = new PaymentService();
        ReflectionTestUtils.setField(payments, "keySecret", "key_secret");
        ReflectionTestUtils.setField(payments, "webhookSecret", SECRET);
        mvc = MockMvcBuilders
                .standaloneSetup(new PaymentWebhookController(payments, orderService, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String event(String name) {
        // non-ASCII on purpose: the signature is over the raw UTF-8 bytes
        return "{\"entity\":\"event\",\"event\":\"" + name + "\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"amount\":249900,\"currency\":\"INR\","
                + "\"status\":\"captured\",\"notes\":{\"brand\":\"BINDASS ₹\"}}}}}";
    }

    private org.springframework.test.web.servlet.ResultActions send(String body, String signature) throws Exception {
        var req = post(URL).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) req.header("X-Razorpay-Signature", signature);
        return mvc.perform(req);
    }

    @Test
    void paymentCaptured_withValidSignature_confirmsTheOrder() throws Exception {
        String body = event("payment.captured");
        send(body, sign(body, SECRET)).andExpect(status().isOk());
        verify(orderService).handleCapturedPayment("order_1", "pay_1", 249900L, "INR");
    }

    @Test
    void orderPaid_isHandledTheSameWay() throws Exception {
        String body = event("order.paid");
        send(body, sign(body, SECRET)).andExpect(status().isOk());
        verify(orderService).handleCapturedPayment("order_1", "pay_1", 249900L, "INR");
    }

    @Test
    void missingSignature_isRejected_andNothingIsProcessed() throws Exception {
        send(event("payment.captured"), null).andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void wrongSignature_isRejected_andNothingIsProcessed() throws Exception {
        String body = event("payment.captured");
        send(body, sign(body, "attacker_guess")).andExpect(status().isBadRequest());
        send(body, "deadbeef").andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void bodyTamperedAfterSigning_isRejected() throws Exception {
        String body = event("payment.captured");
        String sig = sign(body, SECRET);
        send(body.replace("249900", "100"), sig).andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void noWebhookSecretConfigured_failsClosedWith503() throws Exception {
        ReflectionTestUtils.setField(payments, "webhookSecret", "");
        String body = event("payment.captured");
        // even a perfectly signed call can't be trusted when the server has no secret to check it with
        send(body, sign(body, SECRET)).andExpect(status().isServiceUnavailable());
        send(body, "anything").andExpect(status().isServiceUnavailable());
        verifyNoInteractions(orderService);
    }

    @Test
    void paymentFailed_isAcknowledged_butChangesNothing() throws Exception {
        String body = event("payment.failed");
        send(body, sign(body, SECRET)).andExpect(status().isOk());
        verifyNoInteractions(orderService);
    }

    @Test
    void unknownEvents_areAcknowledged_soRazorpayDoesNotRetryThem() throws Exception {
        String body = event("refund.processed");
        send(body, sign(body, SECRET)).andExpect(status().isOk());
        verifyNoInteractions(orderService);
    }

    @Test
    void signedButMalformedJson_is400() throws Exception {
        String body = "{ this is not json";
        send(body, sign(body, SECRET)).andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void missingFieldsInASignedCapture_arePassedOnAsBlank_andTheServiceIgnoresThem() throws Exception {
        String body = "{\"event\":\"payment.captured\",\"payload\":{}}";
        send(body, sign(body, SECRET)).andExpect(status().isOk());
        verify(orderService).handleCapturedPayment(null, null, -1L, "");
    }

    @Test
    void unexpectedServerError_surfacesAs5xx_soRazorpayRetries() throws Exception {
        String body = event("payment.captured");
        doThrow(new IllegalStateException("db down")).when(orderService)
                .handleCapturedPayment(any(), any(), anyLong(), any());
        send(body, sign(body, SECRET)).andExpect(status().is5xxServerError());
    }
}
