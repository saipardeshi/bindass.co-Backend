package com.bindass.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {

    @NotEmpty(message = "Order must have at least one item")
    private List<OrderItemRequest> items;

    @NotNull(message = "Shipping address is required")
    private ShippingAddressRequest shippingAddress;

    private String paymentMethod = "razorpay";

    // ── Inner DTOs ───────────────────────────────────────────
    @Data
    public static class OrderItemRequest {
        @NotBlank private String productId;
        @NotBlank private String size;
        @Min(1)  private int quantity;
    }

    @Data
    public static class ShippingAddressRequest {
        @NotBlank private String name;
        @NotBlank private String phone;
        @NotBlank private String line1;
        private String line2;
        @NotBlank private String city;
        @NotBlank private String state;
        @NotBlank @Size(min=6, max=6) private String pincode;
        private String country = "India";
    }
}