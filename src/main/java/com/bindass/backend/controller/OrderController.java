package com.bindass.backend.controller;

import com.bindass.backend.dto.request.OrderRequest;
import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.model.Order;
import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // POST /api/orders — place order + get Razorpay order ID
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOrder(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody OrderRequest request) {
        Map<String, Object> result = orderService.createOrder(user.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Order created", result));
    }

    // POST /api/orders/verify-payment
    @PostMapping("/verify-payment")
    public ResponseEntity<ApiResponse<Order>> verifyPayment(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody Map<String, String> body) {
        Order order = orderService.verifyPayment(
                user.getUsername(),
                body.get("orderId"),
                body.get("razorpayOrderId"),
                body.get("razorpayPaymentId"),
                body.get("razorpaySignature")
        );
        return ResponseEntity.ok(ApiResponse.ok("Payment verified", order));
    }

    // GET /api/orders/my-orders
    @GetMapping("/my-orders")
    public ResponseEntity<ApiResponse<Page<Order>>> getMyOrders(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Order> orders = orderService.getMyOrders(user.getUsername(), page, size);
        return ResponseEntity.ok(ApiResponse.ok("Orders fetched", orders));
    }

    // GET /api/orders/{id}
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Order>> getOrder(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable String id) {
        return ResponseEntity.ok(
                ApiResponse.ok("Order fetched", orderService.getOrderById(user.getUsername(), id))
        );
    }

    // PUT /api/orders/{id}/cancel
    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Order>> cancel(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable String id) {
        return ResponseEntity.ok(
                ApiResponse.ok("Order cancelled", orderService.cancelOrder(user.getUsername(), id))
        );
    }

    // GET /api/orders — admin only
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<Order>>> getAllOrders(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String status) {
        OrderStatus orderStatus = status != null
                ? OrderStatus.valueOf(status.toUpperCase()) : null;
        return ResponseEntity.ok(
                ApiResponse.ok("All orders", orderService.getAllOrders(page, size, orderStatus))
        );
    }

    // PUT /api/orders/{id}/status — admin only
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Order>> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        Order order = orderService.updateStatus(
                id,
                body.get("status"),
                body.get("trackingNumber"),
                body.get("carrier"),
                body.get("note")
        );
        return ResponseEntity.ok(ApiResponse.ok("Status updated", order));
    }
}