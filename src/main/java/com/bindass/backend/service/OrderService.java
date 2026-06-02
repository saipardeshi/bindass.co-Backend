package com.bindass.backend.service;

import com.bindass.backend.dto.request.OrderRequest;
import com.bindass.backend.exception.*;
import com.bindass.backend.model.Order;
import com.bindass.backend.model.Product;
import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.model.enums.PaymentStatus;
import com.bindass.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository   orderRepository;
    private final UserRepository    userRepository;
    private final ProductRepository productRepository;
    private final PaymentService    paymentService;
    private final ProductService    productService;
    private final EmailService      emailService;

    // ── Create order + Razorpay order ────────────────────────
    public Map<String, Object> createOrder(String userEmail, OrderRequest req) {
        String userId = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();

        // 1. Build order items + validate stock
        List<Order.OrderItem> items = new ArrayList<>();
        for (OrderRequest.OrderItemRequest itemReq : req.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + itemReq.getProductId()
                    ));

            // Check stock
            int available = product.getStock().getOrDefault(itemReq.getSize(), 0);
            if (available < itemReq.getQuantity()) {
                throw new BadRequestException(
                        product.getName() + " (" + itemReq.getSize() + ")"
                                + " — only " + available + " left in stock"
                );
            }

            items.add(Order.OrderItem.builder()
                    .productId(product.getId())
                    .name(product.getName())
                    .image(product.getImages().isEmpty() ? "" : product.getImages().get(0))
                    .price(product.getPrice())
                    .size(itemReq.getSize())
                    .quantity(itemReq.getQuantity())
                    .build());
        }

        // 2. Calculate totals
        double subtotal    = items.stream()
                .mapToDouble(i -> i.getPrice() * i.getQuantity()).sum();
        double shipping    = subtotal >= 1299 ? 0 : 99;
        double totalAmount = subtotal + shipping;

        // 3. Create Razorpay order
        String razorpayOrderId = null;
        try {
            razorpayOrderId = paymentService.createRazorpayOrder(
                    totalAmount, String.valueOf(System.currentTimeMillis())
            );
        } catch (Exception e) {
            throw new RuntimeException("Payment gateway error: " + e.getMessage());
        }

        // 4. Map shipping address
        OrderRequest.ShippingAddressRequest addr = req.getShippingAddress();
        Order.ShippingAddress shippingAddress = Order.ShippingAddress.builder()
                .name(addr.getName()).phone(addr.getPhone())
                .line1(addr.getLine1()).line2(addr.getLine2())
                .city(addr.getCity()).state(addr.getState())
                .pincode(addr.getPincode()).country(addr.getCountry())
                .build();

        // 5. Save order to MongoDB
        Order order = Order.builder()
                .userId(userId)
                .items(items)
                .shippingAddress(shippingAddress)
                .subtotal(subtotal)
                .shippingCost(shipping)
                .totalAmount(totalAmount)
                .paymentMethod(req.getPaymentMethod())
                .razorpayOrderId(razorpayOrderId)
                .statusHistory(new ArrayList<>(List.of(
                        Order.StatusHistory.builder()
                                .status("PENDING").note("Order created").build()
                )))
                .build();

        Order saved = orderRepository.save(order);

        return Map.of(
                "order",          Map.of(
                        "_id",         saved.getId(),
                        "totalAmount", saved.getTotalAmount(),
                        "amount",      (int)(saved.getTotalAmount() * 100) // paise for Razorpay SDK
                ),
                "razorpayOrderId", razorpayOrderId
        );
    }

    // ── Verify payment + confirm order ───────────────────────
    public Order verifyPayment(
            String userEmail,
            String orderId,
            String razorpayOrderId,
            String razorpayPaymentId,
            String razorpaySignature
    ) {
        // 1. Verify HMAC signature
        boolean valid = paymentService.verifySignature(
                razorpayOrderId, razorpayPaymentId, razorpaySignature
        );
        if (!valid) throw new BadRequestException("Invalid payment signature");

        // 2. Update order
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setRazorpayPaymentId(razorpayPaymentId);
        order.setRazorpaySignature(razorpaySignature);
        order.getStatusHistory().add(Order.StatusHistory.builder()
                .status("CONFIRMED").note("Payment verified").build());

        Order saved = orderRepository.save(order);

        // 3. Decrement stock for each item
        for (Order.OrderItem item : saved.getItems()) {
            productService.decrementStock(item.getProductId(), item.getSize(), item.getQuantity());
        }

        // 4. Send confirmation email (fire-and-forget)
        userRepository.findByEmail(userEmail).ifPresent(user -> {
            try { emailService.sendOrderConfirmation(user, saved); }
            catch (Exception e) { log.error("Email failed: {}", e.getMessage()); }
        });

        return saved;
    }

    // ── Get user's orders ────────────────────────────────────
    public Page<Order> getMyOrders(String userEmail, int page, int size) {
        String userId = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();
        Pageable pageable = PageRequest.of(page, size);
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    // ── Get single order ─────────────────────────────────────
    public Order getOrderById(String userEmail, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        String userId = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();

        // Only owner or admin can view
        if (!order.getUserId().equals(userId)) {
            throw new UnauthorizedException("Not authorised to view this order");
        }
        return order;
    }

    // ── Cancel order ─────────────────────────────────────────
    public Order cancelOrder(String userEmail, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        String userId = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();

        if (!order.getUserId().equals(userId)) {
            throw new UnauthorizedException("Not authorised");
        }

        if (!List.of(OrderStatus.PENDING, OrderStatus.CONFIRMED).contains(order.getStatus())) {
            throw new BadRequestException(
                    "Cannot cancel order with status: " + order.getStatus()
            );
        }

        // Refund if already paid
        if (order.getPaymentStatus() == PaymentStatus.PAID
                && order.getRazorpayPaymentId() != null) {
            try {
                paymentService.initiateRefund(
                        order.getRazorpayPaymentId(), order.getTotalAmount()
                );
                order.setPaymentStatus(PaymentStatus.REFUNDED);
            } catch (Exception e) {
                log.error("Refund failed: {}", e.getMessage());
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        order.getStatusHistory().add(Order.StatusHistory.builder()
                .status("CANCELLED").note("Cancelled by customer").build());

        Order saved = orderRepository.save(order);

        // Restore stock
        for (Order.OrderItem item : saved.getItems()) {
            productService.incrementStock(
                    item.getProductId(), item.getSize(), item.getQuantity()
            );
        }

        // Send cancellation email
        userRepository.findByEmail(userEmail).ifPresent(user -> {
            try { emailService.sendCancellationEmail(user, saved); }
            catch (Exception e) { log.error("Email failed: {}", e.getMessage()); }
        });

        return saved;
    }

    // ── Admin: get all orders ─────────────────────────────────
    public Page<Order> getAllOrders(int page, int size, OrderStatus status) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by("createdAt").descending());
        if (status != null) {
            return orderRepository.findByStatus(status, pageable);
        }
        return orderRepository.findAll(pageable);
    }

    // ── Admin: update order status ────────────────────────────
    public Order updateStatus(
            String orderId, String status,
            String trackingNumber, String carrier, String note
    ) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        OrderStatus newStatus = OrderStatus.valueOf(status.toUpperCase());
        order.setStatus(newStatus);
        if (trackingNumber != null) order.setTrackingNumber(trackingNumber);
        if (carrier        != null) order.setCarrier(carrier);
        if (newStatus == OrderStatus.DELIVERED) order.setDeliveredAt(LocalDateTime.now());
        if (newStatus == OrderStatus.CANCELLED) order.setCancelledAt(LocalDateTime.now());

        order.getStatusHistory().add(Order.StatusHistory.builder()
                .status(status.toUpperCase())
                .note(note != null ? note : "Status updated to " + status)
                .build());

        Order saved = orderRepository.save(order);

        // Trigger emails
        userRepository.findById(saved.getUserId()).ifPresent(user -> {
            try {
                if (newStatus == OrderStatus.SHIPPED)
                    emailService.sendShippingUpdate(user, saved);
                else if (newStatus == OrderStatus.DELIVERED)
                    emailService.sendDeliveryConfirmation(user, saved);
                else if (newStatus == OrderStatus.CANCELLED)
                    emailService.sendCancellationEmail(user, saved);
            } catch (Exception e) {
                log.error("Email failed: {}", e.getMessage());
            }
        });

        return saved;
    }
}