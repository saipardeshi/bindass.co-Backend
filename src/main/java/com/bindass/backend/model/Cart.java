package com.bindass.backend.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// One document per user — the cart is the source of truth server-side.
// The frontend keeps a localStorage mirror for guest/offline UX, but it is
// merged into this document on login rather than being authoritative.
@Document(collection = "carts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart {

    @Id
    private String id;

    // One cart per user — enforced via unique index, and looked up by this
    // rather than by cart id from the client.
    @Indexed(unique = true)
    private String userId;

    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // ── Embedded: single cart line item ──────────────────────
    // Deliberately does NOT store price — price is always re-read from the
    // live Product at cart-view time and at checkout, so a stale cart never
    // grants a stale (potentially lower) price. See CartService.enrich().
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CartItem {
        private String productId;
        private String size;
        private int quantity;
    }
}