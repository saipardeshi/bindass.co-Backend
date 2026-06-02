package com.bindass.backend.model.enums;

public enum PaymentStatus {
    PENDING,   // Awaiting payment
    PAID,      // Payment successful
    FAILED,    // Payment failed
    REFUNDED   // Amount refunded
}