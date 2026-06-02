package com.bindass.backend.model;

import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.model.enums.PaymentStatus;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    private String id;

    private String userId; // Reference to User._id

    // Line items — embedded inside the order document
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    private ShippingAddress shippingAddress;

    private double subtotal;
    private double shippingCost;
    private double discount;
    private double totalAmount;

    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Builder.Default
    private String paymentMethod = "razorpay";

    // Razorpay IDs stored for verification + refunds
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String razorpaySignature;

    private String trackingNumber;
    private String carrier;

    // History of status changes (audit trail)
    @Builder.Default
    private List<StatusHistory> statusHistory = new ArrayList<>();

    private LocalDateTime deliveredAt;
    private LocalDateTime cancelledAt;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // ── Embedded: single line item ───────────────────────────
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class OrderItem {
        private String productId;
        private String name;
        private String image;
        private double price;
        private String size;
        private int quantity;
    }

    // ── Embedded: shipping address snapshot ─────────────────
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ShippingAddress {
        private String name;
        private String phone;
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String pincode;
        @Builder.Default
        private String country = "India";
    }

    // ── Embedded: status history entry ──────────────────────
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StatusHistory {
        private String status;
        private String note;
        @Builder.Default
        private LocalDateTime updatedAt = LocalDateTime.now();
    }
}