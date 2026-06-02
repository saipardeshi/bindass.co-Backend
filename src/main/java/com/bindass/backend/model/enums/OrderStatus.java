package com.bindass.backend.model.enums;

public enum OrderStatus {
    PENDING,      // Order created, payment not done
    CONFIRMED,    // Payment verified
    PROCESSING,   // Being packed
    SHIPPED,      // Handed to courier
    DELIVERED,    // Reached customer
    CANCELLED,    // Cancelled by user or admin
    REFUNDED      // Refund processed
}