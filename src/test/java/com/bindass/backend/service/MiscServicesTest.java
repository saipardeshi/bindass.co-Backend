package com.bindass.backend.service;

import com.bindass.backend.dto.request.ReviewRequest;
import com.bindass.backend.exception.*;
import com.bindass.backend.model.Order;
import com.bindass.backend.model.Review;
import com.bindass.backend.model.User;
import com.bindass.backend.model.enums.OrderStatus;
import com.bindass.backend.model.enums.PaymentStatus;
import com.bindass.backend.model.enums.Role;
import com.bindass.backend.repository.*;
import com.bindass.backend.security.JwtTokenProvider;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MiscServicesTest {

    // ───────────── PaymentService ─────────────
    @Test
    void paymentSignature_acceptsOnlyAValidHmac() throws Exception {
        PaymentService p = new PaymentService();
        ReflectionTestUtils.setField(p, "keyId", "rzp_test_x");
        ReflectionTestUtils.setField(p, "keySecret", "topsecret");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("topsecret".getBytes(), "HmacSHA256"));
        String good = HexFormat.of().formatHex(mac.doFinal("order_1|pay_1".getBytes()));

        assertTrue(p.verifySignature("order_1", "pay_1", good));
        assertFalse(p.verifySignature("order_1", "pay_1", "deadbeef"));
        assertFalse(p.verifySignature("order_2", "pay_1", good));   // signature is bound to the order id
        assertFalse(p.verifySignature(null, "pay_1", good));
    }

    // ───────────── ProductService stock ─────────────
    @Mock ProductRepository productRepository;
    @Mock MongoTemplate mongoTemplate;
    @InjectMocks ProductService productService;

    @Test
    void stockDecrement_isAtomicConditional_andReportsFailure() {
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(com.bindass.backend.model.Product.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertFalse(productService.tryDecrementStock("p1", "M", 3), "no matching doc (not enough stock) → false");

        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(com.bindass.backend.model.Product.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        assertTrue(productService.tryDecrementStock("p1", "M", 3));
    }

    @Test
    void stockOps_rejectUnsafeSizeKeys() {
        assertThrows(BadRequestException.class, () -> productService.tryDecrementStock("p1", "M.$set", 1));
        assertThrows(BadRequestException.class, () -> productService.incrementStock("p1", "", 1));
    }

    @Test
    void listing_rejectsBadPaging() {
        assertThrows(BadRequestException.class, () -> productService.getProducts(-1, 12, "newest", null, null, null, false, null));
        assertThrows(BadRequestException.class, () -> productService.getProducts(0, 0, "newest", null, null, null, false, null));
    }

    // ───────────── ReviewService ─────────────
    @Mock ReviewRepository reviewRepository;
    @Mock OrderRepository orderRepository;
    @Mock UserRepository userRepository;
    @Mock ProductService productServiceMock;
    @InjectMocks ReviewService reviewService;

    private Order order(OrderStatus st, PaymentStatus pay) {
        return Order.builder().status(st).paymentStatus(pay)
                .items(List.of(Order.OrderItem.builder().productId("p1").build())).build();
    }

    private Review createReviewWithOrders(Order... orders) {
        when(userRepository.findByEmailIgnoreCase("me@x.com")).thenReturn(Optional.of(User.builder().id("u1").build()));
        when(reviewRepository.existsByUserIdAndProductId("u1", "p1")).thenReturn(false);
        when(orderRepository.findByUserIdOrderByCreatedAtDesc(eq("u1"), any(Pageable.class))).thenReturn(new PageImpl<>(java.util.Arrays.asList(orders)));
        when(reviewRepository.save(any(Review.class))).thenAnswer(i -> i.getArgument(0));
        when(reviewRepository.findByProductIdOrderByCreatedAtDesc("p1")).thenReturn(List.of());
        ReviewRequest req = new ReviewRequest();
        req.setRating(5); req.setTitle("<b>Great</b>"); req.setComment("<script>alert(1)</script>nice");
        return reviewService.createReview("me@x.com", "p1", req);
    }

    @Test
    void verifiedPurchase_requiresAPaidNonCancelledOrder() {
        assertTrue(createReviewWithOrders(order(OrderStatus.DELIVERED, PaymentStatus.PAID)).isVerifiedPurchase());
        assertFalse(createReviewWithOrders(order(OrderStatus.PENDING, PaymentStatus.PENDING)).isVerifiedPurchase(), "unpaid order");
        assertFalse(createReviewWithOrders(order(OrderStatus.CANCELLED, PaymentStatus.REFUNDED)).isVerifiedPurchase(), "cancelled order");
    }

    @Test
    void reviewText_hasMarkupStripped() {
        Review r = createReviewWithOrders();
        assertEquals("Great", r.getTitle());
        assertFalse(r.getComment().contains("<"));
        assertTrue(r.getComment().contains("nice"));
    }

    @Test
    void review_forUnknownProduct_is404() {
        when(userRepository.findByEmailIgnoreCase("me@x.com")).thenReturn(Optional.of(User.builder().id("u1").build()));
        when(productServiceMock.getById("ghost")).thenThrow(new ResourceNotFoundException("Product not found"));
        ReviewRequest req = new ReviewRequest(); req.setRating(4);
        assertThrows(ResourceNotFoundException.class, () -> reviewService.createReview("me@x.com", "ghost", req));
    }

    @Test
    void deletingSomeoneElsesReview_isForbidden() {
        when(userRepository.findByEmailIgnoreCase("me@x.com")).thenReturn(Optional.of(User.builder().id("u1").build()));
        when(reviewRepository.findById("r1")).thenReturn(Optional.of(Review.builder().userId("u2").productId("p1").build()));
        assertThrows(ForbiddenException.class, () -> reviewService.deleteReview("me@x.com", "r1"));
    }

    // ───────────── AuthService ─────────────
    @Test
    void resetPassword_validatesPasswordBeforeAnythingElse() {
        // validatePassword is invoked first, so no repository interaction is needed to prove it
        assertThrows(BadRequestException.class, () -> AuthService.validatePassword(null));
        assertThrows(BadRequestException.class, () -> AuthService.validatePassword("123"));
        assertThrows(BadRequestException.class, () -> AuthService.validatePassword("x".repeat(80)));
        assertDoesNotThrow(() -> AuthService.validatePassword("123456"));
    }

    // ───────────── JWT secret guard ─────────────
    private JwtTokenProvider provider(String secret) {
        JwtTokenProvider p = new JwtTokenProvider();
        ReflectionTestUtils.setField(p, "jwtSecret", secret);
        ReflectionTestUtils.setField(p, "jwtExpiration", 1000L);
        return p;
    }

    @Test
    void jwtProvider_refusesWeakOrPlaceholderSecrets() {
        assertThrows(IllegalStateException.class, () -> provider("").validateSecret());
        assertThrows(IllegalStateException.class, () -> provider("short").validateSecret());
        assertThrows(IllegalStateException.class, () -> provider("change-me-change-me-change-me-change-me-123").validateSecret());
        assertDoesNotThrow(() -> provider("Zk3pQ8vLw0nR7sYh2TgBx9CdEfJ4uMaN1oPiVqWe5XyAkZr6").validateSecret());
    }

    @Test
    void jwtProvider_roundTripsAndRejectsTampering() {
        JwtTokenProvider p = provider("Zk3pQ8vLw0nR7sYh2TgBx9CdEfJ4uMaN1oPiVqWe5XyAkZr6");
        ReflectionTestUtils.setField(p, "jwtExpiration", 60_000L);
        String t = p.generateToken("a@b.com");
        assertTrue(p.validateToken(t));
        assertEquals("a@b.com", p.getEmailFromToken(t));
        assertFalse(p.validateToken(t.substring(0, t.length() - 6) + "AAAAAA"));
    }

    // ───────────── Exception → HTTP status mapping ─────────────
    @Test
    void exceptionHandler_mapsClientErrorsToTheRightStatus() {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        assertEquals(HttpStatus.FORBIDDEN, h.handleForbidden(new AccessDeniedException("x")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, h.handleForbidden(new ForbiddenException("x")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, h.handleUnauthorized(new UnauthorizedException("x")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, h.handleUnreadable(new org.springframework.http.converter.HttpMessageNotReadableException("x", (org.springframework.http.HttpInputMessage) null)).getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY, h.handleGateway(new PaymentGatewayException("x", new RuntimeException())).getStatusCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, h.handleGeneral(new RuntimeException("secret detail")).getStatusCode());
        assertFalse(String.valueOf(h.handleGeneral(new RuntimeException("secret detail")).getBody().getMessage()).contains("secret detail"));
    }

    // ───────────── Admin guard ─────────────
    @Test
    void admin_cannotChangeOwnRole_orDemoteTheLastAdmin() {
        UserRepository adminUserRepo = mock(UserRepository.class);
        AdminService svc = new AdminService(mock(OrderRepository.class), mock(ProductRepository.class), adminUserRepo, mock(MongoTemplate.class));
        User admin = User.builder().id("a1").email("boss@x.com").role(Role.ADMIN).build();
        User other = User.builder().id("a2").email("two@x.com").role(Role.ADMIN).build();
        when(adminUserRepo.findById("a1")).thenReturn(Optional.of(admin));
        when(adminUserRepo.findById("a2")).thenReturn(Optional.of(other));
        when(adminUserRepo.countByRole(Role.ADMIN)).thenReturn(1L);

        assertThrows(BadRequestException.class, () -> svc.toggleUserRole("boss@x.com", "a1"), "self");
        assertThrows(BadRequestException.class, () -> svc.toggleUserRole("boss@x.com", "a2"), "last admin");
    }
}
