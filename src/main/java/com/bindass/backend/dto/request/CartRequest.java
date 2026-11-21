package com.bindass.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

public class CartRequest {

    public static final int MAX_QTY_PER_LINE = 10;

    // ── POST /api/cart/items — add or increment a line item ─────
    @Data
    public static class AddItemRequest {
        @NotBlank private String productId;
        @NotBlank @Size(max = 10) private String size;
        @Min(1) @Max(MAX_QTY_PER_LINE) private int quantity = 1;
    }

    // ── PUT /api/cart/items — set exact quantity for a line item ─
    @Data
    public static class UpdateItemRequest {
        @NotBlank private String productId;
        @NotBlank @Size(max = 10) private String size;
        @Min(0) @Max(MAX_QTY_PER_LINE) private int quantity; // 0 removes the item
    }

    // ── POST /api/cart/merge — merge a guest (localStorage) cart
    //     into the server cart on login ───────────────────────────
    @Data
    public static class MergeRequest {
        @Size(max = 50, message = "Too many items in the guest cart")
        private List<AddItemRequest> items; // sanitised (not rejected) by CartService.mergeGuestCart
    }
}
