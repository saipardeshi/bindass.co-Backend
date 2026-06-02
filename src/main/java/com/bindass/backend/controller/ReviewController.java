package com.bindass.backend.controller;

import com.bindass.backend.dto.request.ReviewRequest;
import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.model.Review;
import com.bindass.backend.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<List<Review>>> getReviews(
            @PathVariable String productId) {
        return ResponseEntity.ok(
                ApiResponse.ok("Reviews fetched",
                        reviewService.getProductReviews(productId))
        );
    }

    @PostMapping("/{productId}")
    public ResponseEntity<ApiResponse<Review>> create(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable String productId,
            @Valid @RequestBody ReviewRequest request) {
        Review review = reviewService.createReview(
                user.getUsername(), productId, request
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Review submitted", review));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable String id) {
        reviewService.deleteReview(user.getUsername(), id);
        return ResponseEntity.ok(ApiResponse.ok("Review deleted"));
    }
}