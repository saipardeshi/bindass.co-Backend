package com.bindass.backend.service;

import com.bindass.backend.dto.request.ReviewRequest;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ForbiddenException;
import com.bindass.backend.exception.ResourceNotFoundException;
import com.bindass.backend.model.Order;
import com.bindass.backend.model.Review;
import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.model.enums.PaymentStatus;
import com.bindass.backend.repository.OrderRepository;
import com.bindass.backend.repository.ReviewRepository;
import com.bindass.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

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
        String userId = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();

        // the product must exist (reviews used to be accepted for made-up ids)
        productService.getById(productId);

        if (reviewRepository.existsByUserIdAndProductId(userId, productId)) {
            throw new BadRequestException("You have already reviewed this product");
        }

        Review review = Review.builder()
                .userId(userId)
                .productId(productId)
                .rating(req.getRating())
                .title(stripHtml(req.getTitle()))
                .comment(stripHtml(req.getComment()))
                .isVerifiedPurchase(hasPurchased(userId, productId))
                .build();

        Review saved = reviewRepository.save(review);
        recalculateRating(productId);
        return saved;
    }

    /** "Verified purchase" = a PAID order that was not cancelled/refunded (unpaid or cancelled orders don't count). */
    private boolean hasPurchased(String userId, String productId) {
        return orderRepository
                .findByUserIdOrderByCreatedAtDesc(userId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream()
                .filter(o -> o.getPaymentStatus() == PaymentStatus.PAID)
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED && o.getStatus() != OrderStatus.REFUNDED)
                .map(Order::getItems)
                .anyMatch(items -> items.stream().anyMatch(i -> productId.equals(i.getProductId())));
    }

    // ── Delete review ────────────────────────────────────────
    public void deleteReview(String userEmail, String reviewId) {
        String userId = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        if (!review.getUserId().equals(userId)) {
            throw new ForbiddenException("You are not allowed to delete this review");
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

    /** Account deletion: remove the user's reviews and fix the affected products' ratings. */
    public void deleteAllByUser(String userId) {
        List<Review> mine = reviewRepository.findByUserId(userId);
        if (mine.isEmpty()) return;
        Set<String> productIds = mine.stream().map(Review::getProductId).collect(Collectors.toSet());
        reviewRepository.deleteAll(mine);
        productIds.forEach(this::recalculateRating);
    }

    // ── Recalculate avg rating after add/delete ───────────────
    private void recalculateRating(String productId) {
        List<Review> reviews = reviewRepository.findByProductIdOrderByCreatedAtDesc(productId);
        OptionalDouble avg = reviews.stream().mapToInt(Review::getRating).average();
        double rounded = avg.isPresent() ? Math.round(avg.getAsDouble() * 10.0) / 10.0 : 0.0;
        productService.updateRating(productId, rounded, reviews.size());
    }

    /** Reviews are plain text — drop any markup so it can never be rendered as HTML. */
    static String stripHtml(String s) {
        if (s == null) return null;
        return s.replaceAll("<[^>]*>", "").replace("<", "").replace(">", "").trim();
    }
}
