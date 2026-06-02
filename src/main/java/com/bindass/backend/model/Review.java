package com.bindass.backend.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "reviews")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    private String id;

    private String userId;
    private String productId;

    private int rating;     // 1-5
    private String title;
    private String comment;

    @Builder.Default
    private List<String> images = new ArrayList<>();

    @Builder.Default
    private boolean isVerifiedPurchase = false; // true if user actually bought the product

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}