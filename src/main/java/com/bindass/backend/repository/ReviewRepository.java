package com.bindass.backend.repository;

import com.bindass.backend.model.Review;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends MongoRepository<Review, String> {

    // All reviews for a product
    List<Review> findByProductIdOrderByCreatedAtDesc(String productId);

    // Check if user already reviewed a product (enforce one review per user)
    boolean existsByUserIdAndProductId(String userId, String productId);

    // Find specific review by user + product (for update/delete)
    Optional<Review> findByUserIdAndProductId(String userId, String productId);

    // Count reviews for a product
    long countByProductId(String productId);

    // All reviews by a user
    List<Review> findByUserId(String userId);
}