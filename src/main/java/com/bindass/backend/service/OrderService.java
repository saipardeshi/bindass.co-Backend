package com.bindass.backend.service;

import com.bindass.backend.dto.request.OrderRequest;
import com.bindass.backend.exception.*;
import com.bindass.backend.model.Order;
import com.bindass.backend.model.Product;
import com.bindass.backend.model.User;
import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.model.enums.PaymentStatus;
import com.bindass.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private static final int MAX_PAGE_SIZE = 50;

    private final OrderRepository   orderRepository;
    private final UserRepository    userRepository;
    private final ProductRepository productRepository;
    private final PaymentService    paymentService;
    private final ProductService    productService;
    private final EmailService      emailService;
    private final MongoTemplate     mongoTemplate;

    // ── Create order + Razorpay order ────────────────────────
    public Map<String, Object> createOrder(String userEmail, OrderRequest req) {
        String userId = findUser(userEmail).getId();

        // 1. Merge duplicate lines (same product+size) so stock is checked against the TOTAL
        Map<String, OrderRequest.OrderItemRequest> merged = new LinkedHashMap<>();
        for (OrderRequest.OrderItemRequest line : req.getItems()) {
            String key = line.getProductId() + "|" + line.getSize();
            OrderRequest.OrderItemRequest existing = merged.get(key);
            if (existing == null) {
                OrderRequest.OrderItemRequest copy = new OrderRequest.OrderItemRequest();
                copy.setProductId(line.getProductId());
                copy.setSize(line.getSize());
                copy.setQuantity(line.getQuantity());
                merged.put(key, copy);
            } else {
                existing.setQuantity(existing.getQuantity() + line.getQuantity());
            }
        }

        // 2. Build order items from DB prices (never trust the client) + validate stock
        List<Order.OrderItem> items = new ArrayList<>();
        for (OrderRequest.OrderItemRequest itemReq : merged.values()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + itemReq.getProductId()));

            if (!product.isPublished()) {
                throw new BadRequestException(product.getName() + " is not available");
            }
            if (itemReq.getQuantity() > 10) {
                throw new BadRequestException("You can order at most 10 of " + product.getName());
            }

            int available = product.getStock() == null ? 0 : product.getStock().getOrDefault(itemReq.getSize(), 0);
            if (available < itemReq.getQuantity()) {
                throw new BadRequestException(
                        product.getName() + " (" + itemReq.getSize() + ")"
                                + " — only " + Math.max(available, 0) + " left in stock");
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

        // 3. Totals
        double subtotal    = items.stream().mapToDouble(i -> i.getPrice() * i.getQuantity()).sum();
        double shipping    = subtotal >= 1299 ? 0 : 99;
        double totalAmount = subtotal + shipping;

        // 4. Razorpay order
        String razorpayOrderId;
        try {
            razorpayOrderId = paymentService.createRazorpayOrder(
                    totalAmount, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            throw new PaymentGatewayException("Could not create the payment order", e);
        }

        // 5. Shipping address snapshot
        OrderRequest.ShippingAddressRequest addr = req.getShippingAddress();
        Order.ShippingAddress shippingAddress = Order.ShippingAddress.builder()
                .name(addr.getName().trim()).phone(addr.getPhone().trim())
                .line1(addr.getLine1().trim()).line2(addr.getLine2())
                .city(addr.getCity().trim()).state(addr.getState().trim())
                .pincode(addr.getPincode().trim()).country(addr.getCountry())
                .build();

        Order order = Order.builder()
                .userId(userId)
                .items(items)
                .shippingAddress(shippingAddress)
                .subtotal(subtotal)
                .shippingCost(shipping)
                .totalAmount(totalAmount)
                .paymentMethod(req.getPaymentMethod())
                .razorpayOrderId(razorpayOrderId)
                .statusHistory(new ArrayList<>(List.of(history("PENDING", "Order created"))))
                .build();

        Order saved = orderRepository.save(order);

        return Map.of(
                "order", Map.of(
                        "_id",         saved.getId(),
                        "id",          saved.getId(),
                        "totalAmount", saved.getTotalAmount(),
                        "amount",      (int) Math.round(saved.getTotalAmount() * 100) // paise
                ),
                "razorpayOrderId", razorpayOrderId,
                "razorpayKeyId",   paymentService.getKeyId()
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
        if (isBlank(razorpayOrderId) || isBlank(razorpayPaymentId) || isBlank(razorpaySignature)) {
            throw new BadRequestException("Payment details are incomplete");
        }
        razorpayOrderId   = razorpayOrderId.trim();
        razorpayPaymentId = razorpayPaymentId.trim();

        // 1. Find the order
        Order order = null;
        if (!isBlank(orderId)) {
            order = orderRepository.findById(orderId.trim()).orElse(null);
        }
        if (order == null) {
            order = orderRepository.findByRazorpayOrderId(razorpayOrderId).orElse(null);
        }
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }

        // 2. Ownership
        if (!order.getUserId().equals(findUser(userEmail).getId())) {
            throw new ForbiddenException("You are not allowed to verify this order");
        }

        // 3. THE PAYMENT MUST BELONG TO THIS ORDER. The signature only proves that
        //    Razorpay signed (orderId|paymentId); without this check a valid payment for
        //    a cheap order could be replayed to "pay" any other order.
        if (!razorpayOrderId.equals(order.getRazorpayOrderId())) {
            throw new BadRequestException("This payment does not belong to this order");
        }

        // 4. Signature
        if (!paymentService.verifySignature(razorpayOrderId, razorpayPaymentId, razorpaySignature)) {
            throw new BadRequestException("Invalid payment signature");
        }

        // 5. Idempotent replay of the SAME payment is fine
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            if (razorpayPaymentId.equals(order.getRazorpayPaymentId())) {
                log.info("Order {} already PAID with this payment — returning as-is", order.getId());
                return order;
            }
            throw new BadRequestException("This order has already been paid");
        }

        // 6. Atomically claim PENDING → CONFIRMED/PAID. A cancelled order can never be re-confirmed,
        //    and two concurrent callbacks can't both win.
        Query claimQuery = new Query(Criteria.where("id").is(order.getId())
                .and("status").is(OrderStatus.PENDING)
                .and("paymentStatus").is(PaymentStatus.PENDING));
        Update claim = new Update()
                .set("status", OrderStatus.CONFIRMED)
                .set("paymentStatus", PaymentStatus.PAID)
                .set("razorpayPaymentId", razorpayPaymentId)
                .set("razorpaySignature", razorpaySignature.trim())
                .set("updatedAt", LocalDateTime.now())
                .push("statusHistory", history("CONFIRMED", "Payment verified"));
        Order confirmed = mongoTemplate.findAndModify(
                claimQuery, claim, FindAndModifyOptions.options().returnNew(true), Order.class);

        if (confirmed == null) {
            Order current = orderRepository.findById(order.getId()).orElse(order);
            if (current.getPaymentStatus() == PaymentStatus.PAID
                    && razorpayPaymentId.equals(current.getRazorpayPaymentId())) {
                return current; // lost a benign race against the identical callback
            }
            throw new BadRequestException("This order is " + current.getStatus()
                    + " and can no longer be paid");
        }

        // 7. Take stock atomically. If a size sold out in the meantime the customer HAS paid,
        //    so keep the order but flag it loudly for manual handling (refund / restock).
        List<String> shortages = new ArrayList<>();
        for (Order.OrderItem item : confirmed.getItems()) {
            boolean ok;
            try {
                ok = productService.tryDecrementStock(item.getProductId(), item.getSize(), item.getQuantity());
            } catch (Exception ex) {
                log.error("Stock update failed for {} {}: {}", item.getProductId(), item.getSize(), ex.getMessage());
                ok = false;
            }
            if (!ok) shortages.add(item.getName() + " (" + item.getSize() + " ×" + item.getQuantity() + ")");
        }
        if (!shortages.isEmpty()) {
            String note = "STOCK SHORTAGE after payment — manual review/refund needed: " + String.join(", ", shortages);
            log.error("Order {}: {}", confirmed.getId(), note);
            mongoTemplate.updateFirst(new Query(Criteria.where("id").is(confirmed.getId())),
                    new Update().push("statusHistory", history("CONFIRMED", note)), Order.class);
            confirmed = orderRepository.findById(confirmed.getId()).orElse(confirmed);
        }

        // 8. Confirmation email (fire-and-forget)
        final Order result = confirmed;
        userRepository.findByEmailIgnoreCase(userEmail).ifPresent(user -> {
            try { emailService.sendOrderConfirmation(user, result); }
            catch (Exception e) { log.error("Email failed: {}", e.getMessage()); }
        });

        return confirmed;
    }

    // ── Get user's orders ────────────────────────────────────
    public Page<Order> getMyOrders(String userEmail, int page, int size) {
        String userId = findUser(userEmail).getId();
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest(page, size, null));
    }

    // ── Get single order ─────────────────────────────────────
    public Order getOrderById(String userEmail, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getUserId().equals(findUser(userEmail).getId())) {
            throw new ForbiddenException("You are not allowed to view this order");
        }
        return order;
    }

    // ── Cancel order (customer) ──────────────────────────────
    public Order cancelOrder(String userEmail, String orderId) {
        User user = findUser(userEmail);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getUserId().equals(user.getId())) {
            throw new ForbiddenException("You are not allowed to cancel this order");
        }
        Order cancelled = doCancel(orderId, "Cancelled by customer");
        sendCancellationEmail(user, cancelled);
        return cancelled;
    }

    /**
     * Shared cancel routine (customer + admin).
     *  - atomically flips PENDING/CONFIRMED → CANCELLED (so it can only happen once)
     *  - stock is restored ONLY if it was actually taken, i.e. the order had been PAID
     *    (previously cancelling an unpaid order invented stock)
     *  - a paid order is refunded; if the gateway refund fails the order is flagged
     *    REFUND_FAILED instead of being left looking "PAID"
     */
    Order doCancel(String orderId, String note) {
        Query q = new Query(Criteria.where("id").is(orderId)
                .and("status").in(OrderStatus.PENDING, OrderStatus.CONFIRMED));
        Update u = new Update()
                .set("status", OrderStatus.CANCELLED)
                .set("cancelledAt", LocalDateTime.now())
                .set("updatedAt", LocalDateTime.now())
                .push("statusHistory", history("CANCELLED", note));
        Order before = mongoTemplate.findAndModify(q, u, FindAndModifyOptions.options().returnNew(false), Order.class);

        if (before == null) {
            Order current = orderRepository.findById(orderId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            throw new BadRequestException("Cannot cancel order with status: " + current.getStatus());
        }

        if (before.getPaymentStatus() == PaymentStatus.PAID) {
            for (Order.OrderItem item : before.getItems()) {
                productService.incrementStock(item.getProductId(), item.getSize(), item.getQuantity());
            }
            PaymentStatus newPayment;
            String refundNote;
            if (before.getRazorpayPaymentId() == null) {
                newPayment = PaymentStatus.REFUND_FAILED;
                refundNote = "No payment id on file — refund must be done manually";
            } else {
                try {
                    paymentService.initiateRefund(before.getRazorpayPaymentId(), before.getTotalAmount());
                    newPayment = PaymentStatus.REFUNDED;
                    refundNote = "Refund initiated";
                } catch (Exception e) {
                    log.error("Refund failed for order {}: {}", orderId, e.getMessage());
                    newPayment = PaymentStatus.REFUND_FAILED;
                    refundNote = "Refund FAILED — issue it manually from the Razorpay dashboard";
                }
            }
            mongoTemplate.updateFirst(new Query(Criteria.where("id").is(orderId)),
                    new Update().set("paymentStatus", newPayment)
                            .push("statusHistory", history("CANCELLED", refundNote)),
                    Order.class);
        }

        return orderRepository.findById(orderId).orElseThrow();
    }

    // ── Admin: get all orders ─────────────────────────────────
    public Page<Order> getAllOrders(int page, int size, OrderStatus status) {
        Pageable pageable = pageRequest(page, size, Sort.by("createdAt").descending());
        return status != null ? orderRepository.findByStatus(status, pageable)
                              : orderRepository.findAll(pageable);
    }

    // ── Admin: update order status ────────────────────────────
    public Order updateStatus(
            String orderId, String status,
            String trackingNumber, String carrier, String note
    ) {
        if (isBlank(status)) throw new BadRequestException("status is required");
        OrderStatus newStatus;
        try {
            newStatus = OrderStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status: " + status);
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BadRequestException("A " + order.getStatus() + " order cannot be changed");
        }

        Order saved;
        if (newStatus == OrderStatus.CANCELLED) {
            saved = doCancel(orderId, note != null ? note : "Cancelled by admin");
        } else {
            if (newStatus == OrderStatus.PENDING || newStatus == OrderStatus.REFUNDED) {
                throw new BadRequestException("Cannot set status to " + newStatus + " manually");
            }
            if (order.getPaymentStatus() != PaymentStatus.PAID) {
                throw new BadRequestException("Order has not been paid yet");
            }
            if (newStatus.ordinal() < order.getStatus().ordinal()) {
                throw new BadRequestException("Cannot move an order back from " + order.getStatus() + " to " + newStatus);
            }
            order.setStatus(newStatus);
            if (trackingNumber != null) order.setTrackingNumber(trackingNumber);
            if (carrier        != null) order.setCarrier(carrier);
            if (newStatus == OrderStatus.DELIVERED) order.setDeliveredAt(LocalDateTime.now());
            order.getStatusHistory().add(history(newStatus.name(),
                    note != null ? note : "Status updated to " + newStatus));
            saved = orderRepository.save(order);
        }

        final Order result = saved;
        userRepository.findById(result.getUserId()).ifPresent(user -> {
            try {
                if (newStatus == OrderStatus.SHIPPED)        emailService.sendShippingUpdate(user, result);
                else if (newStatus == OrderStatus.DELIVERED) emailService.sendDeliveryConfirmation(user, result);
                else if (newStatus == OrderStatus.CANCELLED) emailService.sendCancellationEmail(user, result);
            } catch (Exception e) {
                log.error("Email failed: {}", e.getMessage());
            }
        });

        return saved;
    }

    // ── helpers ──────────────────────────────────────────────
    private User findUser(String email) {
        return userRepository.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void sendCancellationEmail(User user, Order order) {
        try { emailService.sendCancellationEmail(user, order); }
        catch (Exception e) { log.error("Email failed: {}", e.getMessage()); }
    }

    private static Pageable pageRequest(int page, int size, Sort sort) {
        if (page < 0) throw new BadRequestException("page must be 0 or greater");
        if (size < 1) throw new BadRequestException("size must be at least 1");
        size = Math.min(size, MAX_PAGE_SIZE);
        return sort == null ? PageRequest.of(page, size) : PageRequest.of(page, size, sort);
    }

    private static Order.StatusHistory history(String status, String note) {
        return Order.StatusHistory.builder().status(status).note(note).updatedAt(LocalDateTime.now()).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
