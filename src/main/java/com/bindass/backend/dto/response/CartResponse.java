package com.bindass.backend.dto.response;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

// The cart, enriched with live product data at read time.
// This is what CartController actually returns — never the raw Cart
// document — so the client always sees current price/name/image/stock
// rather than what was true when the item was added.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartResponse {

    @Builder.Default
    private List<Item> items = new ArrayList<>();

    private double subtotal;
    private int totalItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String productId;
        private String name;
        private String slug;
        private String image;
        private double price;
        private String size;
        private int quantity;
        private int availableStock;
        // true if the requested quantity now exceeds live stock — lets the
        // frontend warn the user before checkout instead of failing there.
        private boolean stockIssue;
    }
}