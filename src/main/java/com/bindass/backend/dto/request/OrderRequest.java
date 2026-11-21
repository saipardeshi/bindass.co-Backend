package com.bindass.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {

    // @Valid on the list is what makes the per-item constraints below actually run
    @NotEmpty(message = "Order must have at least one item")
    @Size(max = 20, message = "An order can have at most 20 lines")
    private List<@Valid OrderItemRequest> items;

    @NotNull(message = "Shipping address is required")
    @Valid
    private ShippingAddressRequest shippingAddress;

    @Pattern(regexp = "(?i)razorpay", message = "Unsupported payment method")
    private String paymentMethod = "razorpay";

    // ── Inner DTOs ───────────────────────────────────────────
    @Data
    public static class OrderItemRequest {
        @NotBlank(message = "Product is required")
        private String productId;

        @NotBlank(message = "Size is required")
        @Size(max = 10, message = "Invalid size")
        private String size;

        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = 10, message = "You can order at most 10 of one item")
        private int quantity;
    }

    @Data
    public static class ShippingAddressRequest {
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name is too long")
        private String name;

        @NotBlank(message = "Phone is required")
        @Pattern(regexp = "^\\+?[0-9]{10,13}$", message = "Enter a valid phone number (10–13 digits)")
        private String phone;

        @NotBlank(message = "Address line 1 is required")
        @Size(max = 200, message = "Address line 1 is too long")
        private String line1;

        @Size(max = 200, message = "Address line 2 is too long")
        private String line2;

        @NotBlank(message = "City is required")
        @Size(max = 60, message = "City is too long")
        private String city;

        @NotBlank(message = "State is required")
        @Size(max = 60, message = "State is too long")
        private String state;

        @NotBlank(message = "Pincode is required")
        @Pattern(regexp = "^[1-9][0-9]{5}$", message = "Pincode must be a valid 6-digit number")
        private String pincode;

        @Size(max = 60, message = "Country is too long")
        private String country = "India";
    }
}
