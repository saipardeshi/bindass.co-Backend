package com.bindass.backend.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.*;

@Document(collection = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    private String id;

    private String name;

    @Indexed(unique = true)
    private String slug; // URL-friendly name e.g. "shadow-oversized-hoodie"

    @TextIndexed // Enables full-text search on this field
    private String description;

    private double price;
    private Double originalPrice; // null if no discount
    private int discount; // percentage e.g. 28

    // Cloudinary public IDs (not full URLs — use helpers to build URLs)
    @Builder.Default
    private List<String> images = new ArrayList<>();

    @Builder.Default
    private String category = "Unisex Hoodie";

    // Available sizes e.g. ["XS","S","M","L","XL","XXL"]
    @Builder.Default
    private List<String> sizes = List.of("S", "M", "L", "XL");

    // Stock per size: {"S": 10, "M": 15, "L": 12}
    @Builder.Default
    private Map<String, Integer> stock = new HashMap<>();

    @Builder.Default
    private List<String> details = new ArrayList<>(); // fabric, cut details

    @Builder.Default
    private List<String> care = new ArrayList<>(); // wash instructions

    @Builder.Default
    private List<String> tags = new ArrayList<>(); // for search/filtering

    @Builder.Default
    private String material = "100% Ring-Spun Cotton";

    @Builder.Default
    private String weight = "380 GSM";

    @Builder.Default
    private boolean isFeatured = false;

    @Builder.Default
    private boolean isNew = true;

    @Builder.Default
    private boolean isSoldOut = false;

    @Builder.Default
    private boolean isPublished = true;

    @Builder.Default
    private int numReviews = 0;

    @Builder.Default
    private double averageRating = 0.0;

    @Builder.Default
    private int totalSold = 0;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}