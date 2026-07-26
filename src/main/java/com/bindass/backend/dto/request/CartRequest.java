package com.bindass.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

public class CartRequest {

    // ── POST /api/cart/items — add or increment a line item ─────
    @Data
    public static class AddItemRequest {
        @NotBlank private String productId;
        @NotBlank private String size;
        @Min(1) private int quantity = 1;
    }

    // ── PUT /api/cart/items — set exact quantity for a line item ─
    @Data
    public static class UpdateItemRequest {
        @NotBlank private String productId;
        @NotBlank private String size;
        @Min(0) private int quantity; // 0 removes the item
    }

    // ── POST /api/cart/merge — merge a guest (localStorage) cart
    //     into the server cart on login ───────────────────────────
    @Data
    public static class MergeRequest {
        private List<AddItemRequest> items;
    }
}