package com.bindass.backend.service;

import com.bindass.backend.dto.request.OrderRequest;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ForbiddenException;
import com.bindass.backend.exception.PaymentGatewayException;
import com.bindass.backend.model.Order;
import com.bindass.backend.model.Product;
import com.bindass.backend.model.User;
import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.model.enums.PaymentStatus;
import com.bindass.backend.repository.OrderRepository;
import com.bindass.backend.repository.ProductRepository;
import com.bindass.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the payment / stock / cancel bugs found in the QA audit.
 * Pure unit tests (mocked persistence) — they never touch a real database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock OrderRepository   orderRepository;
    @Mock UserRepository    userRepository;
    @Mock ProductRepository productRepository;
    @Mock PaymentService    paymentService;
    @Mock ProductService    productService;
    @Mock EmailService      emailService;
    @Mock MongoTemplate     mongoTemplate;
    @InjectMocks OrderService service;

    static final String ME = "me@x.com";
    User me, other;

    @BeforeEach
    void setUp() {
        me = User.builder().id("u1").email(ME).build();
        other = User.builder().id("u2").email("other@x.com").build();
        when(userRepository.findByEmailIgnoreCase(ME)).thenReturn(Optional.of(me));
        when(userRepository.findByEmailIgnoreCase("other@x.com")).thenReturn(Optional.of(other));
    }

    private Order order(String id, String userId, String rzpOrderId, OrderStatus st, PaymentStatus pay) {
        return Order.builder().id(id).userId(userId).razorpayOrderId(rzpOrderId)
                .status(st).paymentStatus(pay).totalAmount(2499)
                .razorpayPaymentId(pay == PaymentStatus.PAID ? "pay_1" : null)
                .items(new ArrayList<>(List.of(Order.OrderItem.builder()
                        .productId("p1").name("Hoodie").size("M").quantity(2).price(1249.5).build())))
                .statusHistory(new ArrayList<>()).build();
    }

    // ───────────────────────── verifyPayment ─────────────────────────

    @Test
    void verify_rejectsPaymentThatBelongsToAnotherOrder() {
        // order B was created with rzp_B, but the caller presents a (validly signed) payment for rzp_A
        when(orderRepository.findById("B")).thenReturn(Optional.of(order("B", "u1", "rzp_B", OrderStatus.PENDING, PaymentStatus.PENDING)));
        when(paymentService.verifySignature("rzp_A", "pay_A", "sigA")).thenReturn(true);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.verifyPayment(ME, "B", "rzp_A", "pay_A", "sigA"));

        assertTrue(ex.getMessage().contains("does not belong"));
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class));
        verifyNoInteractions(productService);
    }

    @Test
    void verify_rejectsOtherUsersOrder() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u2", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING)));
        assertThrows(ForbiddenException.class, () -> service.verifyPayment(ME, "A", "rzp_A", "pay_A", "sig"));
        verifyNoInteractions(productService);
    }

    @Test
    void verify_rejectsBadSignature() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u1", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING)));
        when(paymentService.verifySignature(any(), any(), any())).thenReturn(false);
        assertThrows(BadRequestException.class, () -> service.verifyPayment(ME, "A", "rzp_A", "pay_A", "bad"));
        verifyNoInteractions(productService);
    }

    @Test
    void verify_rejectsMissingFields() {
        assertThrows(BadRequestException.class, () -> service.verifyPayment(ME, "A", null, "pay", "sig"));
        assertThrows(BadRequestException.class, () -> service.verifyPayment(ME, "A", "rzp", " ", "sig"));
        assertThrows(BadRequestException.class, () -> service.verifyPayment(ME, "A", "rzp", "pay", null));
    }

    @Test
    void verify_happyPath_claimsOrderAndTakesStockOnce() {
        Order pending = order("A", "u1", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING);
        Order confirmed = order("A", "u1", "rzp_A", OrderStatus.CONFIRMED, PaymentStatus.PAID);
        when(orderRepository.findById("A")).thenReturn(Optional.of(pending));
        when(paymentService.verifySignature("rzp_A", "pay_1", "sig")).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(confirmed);
        when(productService.tryDecrementStock("p1", "M", 2)).thenReturn(true);

        Order result = service.verifyPayment(ME, "A", "rzp_A", "pay_1", "sig");

        assertEquals(PaymentStatus.PAID, result.getPaymentStatus());
        verify(productService, times(1)).tryDecrementStock("p1", "M", 2);
    }

    @Test
    void verify_cancelledOrderCannotBeReConfirmedByLatePayment() {
        Order cancelled = order("A", "u1", "rzp_A", OrderStatus.CANCELLED, PaymentStatus.PENDING);
        when(orderRepository.findById("A")).thenReturn(Optional.of(cancelled));
        when(paymentService.verifySignature(any(), any(), any())).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(null); // atomic claim fails

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.verifyPayment(ME, "A", "rzp_A", "pay_late", "sig"));
        assertTrue(ex.getMessage().contains("CANCELLED"));
        verifyNoInteractions(productService);
    }

    @Test
    void verify_replayOfSamePaymentIsIdempotent_andDoesNotTouchStock() {
        Order paid = order("A", "u1", "rzp_A", OrderStatus.CONFIRMED, PaymentStatus.PAID); // paymentId pay_1
        when(orderRepository.findById("A")).thenReturn(Optional.of(paid));
        when(paymentService.verifySignature(any(), any(), any())).thenReturn(true);

        Order r = service.verifyPayment(ME, "A", "rzp_A", "pay_1", "sig");

        assertSame(paid, r);
        verifyNoInteractions(productService);
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class));
    }

    @Test
    void verify_paidOrderCannotBePaidAgainWithADifferentPayment() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u1", "rzp_A", OrderStatus.CONFIRMED, PaymentStatus.PAID)));
        when(paymentService.verifySignature(any(), any(), any())).thenReturn(true);
        assertThrows(BadRequestException.class, () -> service.verifyPayment(ME, "A", "rzp_A", "pay_OTHER", "sig"));
    }

    @Test
    void verify_stockShortageAfterPayment_isFlaggedNotSilentlyIgnored() {
        Order pending = order("A", "u1", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING);
        Order confirmed = order("A", "u1", "rzp_A", OrderStatus.CONFIRMED, PaymentStatus.PAID);
        when(orderRepository.findById("A")).thenReturn(Optional.of(pending));
        when(paymentService.verifySignature(any(), any(), any())).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(confirmed);
        when(productService.tryDecrementStock(any(), any(), anyInt())).thenReturn(false); // sold out meanwhile

        service.verifyPayment(ME, "A", "rzp_A", "pay_1", "sig");

        ArgumentCaptor<UpdateDefinition> upd = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).updateFirst(any(Query.class), upd.capture(), eq(Order.class));
        assertTrue(upd.getValue().getUpdateObject().toString().contains("STOCK SHORTAGE"));
    }

    // ───────────────────────── cancelOrder ─────────────────────────

    @Test
    void cancel_unpaidOrder_doesNotInventStock_andDoesNotRefund() {
        Order before = order("A", "u1", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING);
        when(orderRepository.findById("A")).thenReturn(Optional.of(before));
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(before);

        service.cancelOrder(ME, "A");

        verify(productService, never()).incrementStock(any(), any(), anyInt());
        verifyNoInteractions(paymentService);
    }

    @Test
    void cancel_paidOrder_restoresStockOnce_andRefunds() throws Exception {
        Order paid = order("A", "u1", "rzp_A", OrderStatus.CONFIRMED, PaymentStatus.PAID);
        when(orderRepository.findById("A")).thenReturn(Optional.of(paid));
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(paid);

        service.cancelOrder(ME, "A");

        verify(productService, times(1)).incrementStock("p1", "M", 2);
        verify(paymentService).initiateRefund("pay_1", 2499);
        ArgumentCaptor<UpdateDefinition> upd = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).updateFirst(any(Query.class), upd.capture(), eq(Order.class));
        assertTrue(upd.getValue().getUpdateObject().toString().contains("REFUNDED"));
    }

    @Test
    void cancel_paidOrder_refundFailure_isFlaggedRefundFailed_notLeftAsPaid() throws Exception {
        Order paid = order("A", "u1", "rzp_A", OrderStatus.CONFIRMED, PaymentStatus.PAID);
        when(orderRepository.findById("A")).thenReturn(Optional.of(paid));
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(paid);
        doThrow(new RuntimeException("gateway down")).when(paymentService).initiateRefund(any(), anyDouble());

        service.cancelOrder(ME, "A");

        ArgumentCaptor<UpdateDefinition> upd = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).updateFirst(any(Query.class), upd.capture(), eq(Order.class));
        String json = upd.getValue().getUpdateObject().toString();
        assertTrue(json.contains("REFUND_FAILED"), json);
        assertFalse(json.contains("\"PAID\""), json);
    }

    @Test
    void cancel_alreadyCancelled_isRejected() {
        Order cancelled = order("A", "u1", "rzp_A", OrderStatus.CANCELLED, PaymentStatus.REFUNDED);
        when(orderRepository.findById("A")).thenReturn(Optional.of(cancelled));
        when(mongoTemplate.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Order.class))).thenReturn(null);

        assertThrows(BadRequestException.class, () -> service.cancelOrder(ME, "A"));
        verify(productService, never()).incrementStock(any(), any(), anyInt());
    }

    @Test
    void cancel_otherUsersOrder_isForbidden() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u2", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING)));
        assertThrows(ForbiddenException.class, () -> service.cancelOrder(ME, "A"));
        verifyNoInteractions(productService);
    }

    @Test
    void getOrder_otherUsersOrder_isForbidden() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u2", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING)));
        assertThrows(ForbiddenException.class, () -> service.getOrderById(ME, "A"));
    }

    // ───────────────────────── admin status ─────────────────────────

    @Test
    void adminStatus_unknownStatus_is400_not500() {
        assertThrows(BadRequestException.class, () -> service.updateStatus("A", "NOPE", null, null, null));
        assertThrows(BadRequestException.class, () -> service.updateStatus("A", null, null, null, null));
    }

    @Test
    void adminStatus_cannotShipAnUnpaidOrder() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u1", "rzp_A", OrderStatus.PENDING, PaymentStatus.PENDING)));
        assertThrows(BadRequestException.class, () -> service.updateStatus("A", "SHIPPED", null, null, null));
    }

    @Test
    void adminStatus_cannotChangeACancelledOrder() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u1", "rzp_A", OrderStatus.CANCELLED, PaymentStatus.REFUNDED)));
        assertThrows(BadRequestException.class, () -> service.updateStatus("A", "DELIVERED", null, null, null));
    }

    @Test
    void adminStatus_cannotMoveBackwards() {
        when(orderRepository.findById("A")).thenReturn(Optional.of(order("A", "u1", "rzp_A", OrderStatus.SHIPPED, PaymentStatus.PAID)));
        assertThrows(BadRequestException.class, () -> service.updateStatus("A", "CONFIRMED", null, null, null));
    }

    // ───────────────────────── createOrder ─────────────────────────

    private OrderRequest request(int... qtys) {
        OrderRequest r = new OrderRequest();
        List<OrderRequest.OrderItemRequest> items = new ArrayList<>();
        for (int q : qtys) {
            OrderRequest.OrderItemRequest i = new OrderRequest.OrderItemRequest();
            i.setProductId("p1"); i.setSize("M"); i.setQuantity(q);
            items.add(i);
        }
        r.setItems(items);
        OrderRequest.ShippingAddressRequest a = new OrderRequest.ShippingAddressRequest();
        a.setName("N"); a.setPhone("9999999999"); a.setLine1("L1"); a.setCity("C"); a.setState("S"); a.setPincode("400001");
        r.setShippingAddress(a);
        return r;
    }

    private Product product(int stockM) {
        return Product.builder().id("p1").name("Hoodie").price(2499).images(List.of("img"))
                .stock(new HashMap<>(Map.of("M", stockM))).build();
    }

    @Test
    void create_splitLinesAreMergedBeforeTheStockCheck() {
        // two lines of 3 for the same size = 6 requested, only 5 in stock → must be rejected
        when(productRepository.findById("p1")).thenReturn(Optional.of(product(5)));
        assertThrows(BadRequestException.class, () -> service.createOrder(ME, request(3, 3)));
        verifyNoInteractions(paymentService);
    }

    @Test
    void create_unpublishedProductCannotBeBought() {
        Product p = product(5);
        p.setPublished(false);
        when(productRepository.findById("p1")).thenReturn(Optional.of(p));
        assertThrows(BadRequestException.class, () -> service.createOrder(ME, request(1)));
    }

    @Test
    void create_gatewayFailure_isA502_notAGeneric500() throws Exception {
        when(productRepository.findById("p1")).thenReturn(Optional.of(product(5)));
        when(paymentService.createRazorpayOrder(anyDouble(), any())).thenThrow(new RuntimeException("boom"));
        assertThrows(PaymentGatewayException.class, () -> service.createOrder(ME, request(1)));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_usesDatabasePrice_andShippingRule() throws Exception {
        when(productRepository.findById("p1")).thenReturn(Optional.of(product(5)));
        when(paymentService.createRazorpayOrder(anyDouble(), any())).thenReturn("rzp_new");
        when(paymentService.getKeyId()).thenReturn("rzp_test_x");
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> { Order o = inv.getArgument(0); o.setId("new"); return o; });

        Map<String, Object> out = service.createOrder(ME, request(1));

        @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) out.get("order");
        assertEquals(2499.0, (double) o.get("totalAmount"), 0.001); // 2499 ≥ 1299 → free shipping
        assertEquals("rzp_new", out.get("razorpayOrderId"));
    }
}
