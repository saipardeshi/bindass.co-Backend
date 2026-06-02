package com.bindass.backend.repository;

import com.bindass.backend.model.Order;
import com.bindass.backend.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends MongoRepository<Order, String> {

    // All orders for a specific user, newest first
    Page<Order> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    // Find order by Razorpay order ID (used in payment verification)
    Optional<Order> findByRazorpayOrderId(String razorpayOrderId);

    // Admin: filter by status
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    // Count orders by status (for dashboard)
    long countByStatus(OrderStatus status);
}