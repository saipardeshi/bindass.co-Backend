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

        // 6–8. Claim the order, take stock, send the email (same code path as the webhook)
        return confirmPayment(order, razorpayPaymentId, razorpaySignature.trim(), "Payment verified");
    }

    /**
     * Atomically confirms a PENDING order as PAID, takes stock and sends the confirmation email.
     * Shared by the browser callback ({@link #verifyPayment}) and the Razorpay webhook
     * ({@link #handleCapturedPayment}) so both behave identically and whichever arrives
     * second is a harmless no-op.
     *
     * @param signature the browser-side payment signature, or null when confirmed by webhook
     */
    private Order confirmPayment(Order order, String paymentId, String signature, String note) {
        // Atomically claim PENDING → CONFIRMED/PAID. A cancelled order can never be re-confirmed,
        // and two concurrent callbacks can't both win.
        Query claimQuery = new Query(Criteria.where("id").is(order.getId())
                .and("status").is(OrderStatus.PENDING)
                .and("paymentStatus").is(PaymentStatus.PENDING));
        Update claim = new Update()
                .set("status", OrderStatus.CONFIRMED)
                .set("paymentStatus", PaymentStatus.PAID)
                .set("razorpayPaymentId", paymentId)
                .set("updatedAt", LocalDateTime.now())
                .push("statusHistory", history("CONFIRMED", note));
        if (signature != null) claim.set("razorpaySignature", signature);
        Order confirmed = mongoTemplate.findAndModify(
                claimQuery, claim, FindAndModifyOptions.options().returnNew(true), Order.class);

        if (confirmed == null) {
            Order current = orderRepository.findById(order.getId()).orElse(order);
            if (current.getPaymentStatus() == PaymentStatus.PAID
                    && paymentId.equals(current.getRazorpayPaymentId())) {
                return current; // lost a benign race against the identical callback
            }
            throw new BadRequestException("This order is " + current.getStatus()
                    + " and can no longer be paid");
        }

        // Take stock atomically. If a size sold out in the meantime the customer HAS paid,
        // so keep the order but flag it loudly for manual handling (refund / restock).
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
            String shortageNote = "STOCK SHORTAGE after payment — manual review/refund needed: " + String.join(", ", shortages);
            log.error("Order {}: {}", confirmed.getId(), shortageNote);
            addHistory(confirmed.getId(), "CONFIRMED", shortageNote);
            confirmed = orderRepository.findById(confirmed.getId()).orElse(confirmed);
        }

        // Confirmation email (fire-and-forget)
        final Order result = confirmed;
        userRepository.findById(result.getUserId()).ifPresent(user -> {
            try { emailService.sendOrderConfirmation(user, result); }
            catch (Exception e) { log.error("Email failed: {}", e.getMessage()); }
        });

        return confirmed;
    }

    // ── Razorpay webhook: payment captured ───────────────────
    /**
     * Called by the webhook controller AFTER it has verified Razorpay's signature.
     * It is the safety net for "customer paid but the browser never reached /verify-payment"
     * (tab closed, network drop, crash). It never throws for business reasons — Razorpay
     * retries any non-2xx response, and retrying can't fix a business-rule rejection.
     *
     * @param amountPaise amount Razorpay actually captured, in paise
     */
    public void handleCapturedPayment(String razorpayOrderId, String paymentId, long amountPaise, String currency) {
        if (isBlank(razorpayOrderId) || isBlank(paymentId)) {
            log.warn("Webhook: capture event without order/payment id — ignored");
            return;
        }
        razorpayOrderId = razorpayOrderId.trim();
        paymentId = paymentId.trim();

        Order order = orderRepository.findByRazorpayOrderId(razorpayOrderId).orElse(null);
        if (order == null) {
            log.warn("Webhook: no order for Razorpay order {} (payment {}) — ignored", razorpayOrderId, paymentId);
            return;
        }

        // Never confirm if what was charged isn't what we asked Razorpay to charge.
        long expectedPaise = Math.round(order.getTotalAmount() * 100);
        if (amountPaise != expectedPaise || !"INR".equalsIgnoreCase(currency)) {
            String note = "Webhook payment " + paymentId + " NOT applied: captured " + amountPaise + " "
                    + currency + " but order expects " + expectedPaise + " INR — review manually";
            log.error("Order {}: {}", order.getId(), note);
            addHistory(order.getId(), order.getStatus().name(), note);
            return;
        }

        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            log.info("Webhook: order {} already PAID — nothing to do", order.getId());
            return; // the browser callback got there first
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            refundLatePayment(order, paymentId);
            return;
        }

        try {
            confirmPayment(order, paymentId, null, "Payment confirmed by Razorpay webhook");
            log.info("Webhook: order {} confirmed via payment {}", order.getId(), paymentId);
        } catch (BadRequestException e) {
            // e.g. a different payment already confirmed it, or it was cancelled a moment ago
            log.warn("Webhook: order {} not confirmed — {}", order.getId(), e.getMessage());
        }
    }

    /**
     * The customer paid for an order that was cancelled in the meantime → give the money back.
     * The order is claimed atomically first (so duplicate webhook deliveries can't refund twice)
     * and parked in REFUND_FAILED — the safe "needs a human" state — until the refund succeeds.
     */
    private void refundLatePayment(Order order, String paymentId) {
        Query q = new Query(Criteria.where("id").is(order.getId())
                .and("status").is(OrderStatus.CANCELLED)
                .and("paymentStatus").is(PaymentStatus.PENDING));
        Update u = new Update()
                .set("paymentStatus", PaymentStatus.REFUND_FAILED)
                .set("razorpayPaymentId", paymentId)
                .set("updatedAt", LocalDateTime.now())
                .push("statusHistory", history("CANCELLED", "Payment " + paymentId + " captured after cancellation — refunding"));
        Order claimed = mongoTemplate.findAndModify(q, u, FindAndModifyOptions.options().returnNew(false), Order.class);
        if (claimed == null) return; // another delivery is already handling it

        try {
            paymentService.initiateRefund(paymentId, order.getTotalAmount());
            mongoTemplate.updateFirst(new Query(Criteria.where("id").is(order.getId())),
                    new Update().set("paymentStatus", PaymentStatus.REFUNDED)
                            .push("statusHistory", history("CANCELLED", "Late payment refunded")),
                    Order.class);
            log.info("Webhook: refunded late payment {} for cancelled order {}", paymentId, order.getId());
        } catch (Exception e) {
            log.error("Webhook: refund of late payment {} for order {} FAILED: {}", paymentId, order.getId(), e.getMessage());
            addHistory(order.getId(), "CANCELLED", "Refund FAILED — issue it manually from the Razorpay dashboard");
        }
    }

    private void addHistory(String orderId, String status, String note) {
        mongoTemplate.updateFirst(new Query(Criteria.where("id").is(orderId)),
                new Update().push("statusHistory", history(status, note)), Order.class);
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
