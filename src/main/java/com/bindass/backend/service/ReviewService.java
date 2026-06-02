package com.bindass.backend.service;

import com.bindass.backend.dto.request.ReviewRequest;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ResourceNotFoundException;
import com.bindass.backend.exception.UnauthorizedException;
import com.bindass.backend.model.Review;
import com.bindass.backend.repository.OrderRepository;
import com.bindass.backend.repository.ReviewRepository;
import com.bindass.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.OptionalDouble;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository  reviewRepository;
    private final OrderRepository   orderRepository;
    private final UserRepository    userRepository;
    private final ProductService    productService;

    // ── Get reviews for a product ────────────────────────────
    public List<Review> getProductReviews(String productId) {
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId);
    }

    // ── Create review ────────────────────────────────────────
    public Review createReview(String userEmail, String productId, ReviewRequest req) {
        String userId = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();

        // One review per user per product
        if (reviewRepository.existsByUserIdAndProductId(userId, productId)) {
            throw new BadRequestException("You have already reviewed this product");
        }

        // Check if user actually bought this product
        boolean verifiedPurchase = orderRepository
                .findByUserIdOrderByCreatedAtDesc(userId,
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent()
                .stream()
                .anyMatch(order ->
                        order.getItems().stream()
                                .anyMatch(item -> item.getProductId().equals(productId))
                );

        Review review = Review.builder()
                .userId(userId)
                .productId(productId)
                .rating(req.getRating())
                .title(req.getTitle())
                .comment(req.getComment())
                .isVerifiedPurchase(verifiedPurchase)
                .build();

        Review saved = reviewRepository.save(review);

        // Recalculate product average rating
        recalculateRating(productId);

        return saved;
    }

    // ── Delete review ────────────────────────────────────────
    public void deleteReview(String userEmail, String reviewId) {
        String userId = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        // Only owner can delete their review
        if (!review.getUserId().equals(userId)) {
            throw new UnauthorizedException("Not authorised to delete this review");
        }

        reviewRepository.delete(review);
        recalculateRating(review.getProductId());
    }

    // ── Admin: delete any review ─────────────────────────────
    public void adminDeleteReview(String reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        reviewRepository.delete(review);
        recalculateRating(review.getProductId());
    }

    // ── Recalculate avg rating after add/delete ───────────────
    private void recalculateRating(String productId) {
        List<Review> reviews = reviewRepository.findByProductIdOrderByCreatedAtDesc(productId);
        int count = reviews.size();
        OptionalDouble avg = reviews.stream()
                .mapToInt(Review::getRating)
                .average();
        double rounded = avg.isPresent()
                ? Math.round(avg.getAsDouble() * 10.0) / 10.0
                : 0.0;
        productService.updateRating(productId, rounded, count);
    }
}