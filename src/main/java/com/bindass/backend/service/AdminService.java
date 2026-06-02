package com.bindass.backend.service;

import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.model.enums.Role;
import com.bindass.backend.repository.*;
import com.bindass.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrderRepository   orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository    userRepository;
    private final MongoTemplate     mongoTemplate;

    // ── Dashboard stats ───────────────────────────────────────
    public Map<String, Object> getDashboardStats() {
        long totalOrders   = orderRepository.countByStatus(OrderStatus.CONFIRMED)
                + orderRepository.countByStatus(OrderStatus.SHIPPED)
                + orderRepository.countByStatus(OrderStatus.DELIVERED);
        long totalUsers    = userRepository.count();
        long totalProducts = productRepository.count();
        long pendingOrders = orderRepository.countByStatus(OrderStatus.PENDING);

        // Total revenue via aggregation
        Aggregation revenueAgg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("paymentStatus").is("PAID")),
                Aggregation.group().sum("totalAmount").as("total")
        );
        AggregationResults<Map> revenueResult =
                mongoTemplate.aggregate(revenueAgg, "orders", Map.class);
        double totalRevenue = revenueResult.getMappedResults().isEmpty() ? 0
                : ((Number) revenueResult.getMappedResults().get(0).get("total")).doubleValue();

        // Recent 5 orders
        List<Object> recentOrders = new ArrayList<>(
                orderRepository.findAll(
                        PageRequest.of(0, 5, Sort.by("createdAt").descending())
                ).stream().toList()
        );

        // Top 5 products by sales
        List<Object> topProducts = new ArrayList<>(
                productRepository.findByIsPublishedTrue(
                        PageRequest.of(0, 5, Sort.by("totalSold").descending())
                ).stream().toList()
        );

        return Map.of(
                "totalOrders",   totalOrders,
                "totalRevenue",  totalRevenue,
                "totalUsers",    totalUsers,
                "totalProducts", totalProducts,
                "pendingOrders", pendingOrders,
                "recentOrders",  recentOrders,
                "topProducts",   topProducts
        );
    }

    // ── Revenue by month (last 12 months) ─────────────────────
    public List<Map<String, Object>> getRevenueChart() {
        LocalDateTime twelveMonthsAgo = LocalDateTime.now().minusMonths(11)
                .withDayOfMonth(1).withHour(0).withMinute(0);

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(
                        Criteria.where("paymentStatus").is("PAID")
                                .and("createdAt").gte(twelveMonthsAgo)
                ),
                Aggregation.project("totalAmount")
                        .andExpression("year(createdAt)").as("year")
                        .andExpression("month(createdAt)").as("month"),
                Aggregation.group("year", "month")
                        .sum("totalAmount").as("revenue")
                        .count().as("orders"),
                Aggregation.sort(Sort.by("_id.year", "_id.month").ascending())
        );

        AggregationResults<Map> results =
                mongoTemplate.aggregate(agg, "orders", Map.class);

        String[] months = {"Jan","Feb","Mar","Apr","May","Jun",
                "Jul","Aug","Sep","Oct","Nov","Dec"};

        return results.getMappedResults().stream().map(r -> {
            Map<?, ?> id = (Map<?, ?>) r.get("_id");
            int m = ((Number) id.get("month")).intValue();
            int y = ((Number) id.get("year")).intValue();
            return (Map<String, Object>) new HashMap<String, Object>(Map.of(
                    "month",   months[m - 1] + " " + y,
                    "revenue", r.get("revenue"),
                    "orders",  r.get("orders")
            ));
        }).toList();
    }

    // ── Toggle user role ──────────────────────────────────────
    public Map<String, Object> toggleUserRole(String userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setRole(user.getRole() == Role.ADMIN ? Role.USER : Role.ADMIN);
        userRepository.save(user);
        return Map.of("id", user.getId(), "role", user.getRole());
    }
}