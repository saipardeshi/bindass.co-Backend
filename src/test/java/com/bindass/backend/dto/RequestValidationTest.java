package com.bindass.backend.dto;

import com.bindass.backend.dto.request.*;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The order DTO used to skip validation of its nested items/address (qty 0, blank address, bad pincode all passed). */
class RequestValidationTest {

    static Validator validator;

    @BeforeAll
    static void init() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private OrderRequest valid() {
        OrderRequest r = new OrderRequest();
        OrderRequest.OrderItemRequest i = new OrderRequest.OrderItemRequest();
        i.setProductId("p1"); i.setSize("M"); i.setQuantity(1);
        r.setItems(new ArrayList<>(List.of(i)));
        OrderRequest.ShippingAddressRequest a = new OrderRequest.ShippingAddressRequest();
        a.setName("Sai"); a.setPhone("9999999999"); a.setLine1("1 Street"); a.setCity("Mumbai"); a.setState("MH"); a.setPincode("400001");
        r.setShippingAddress(a);
        return r;
    }

    private boolean fails(Object o, String fragment) {
        Set<ConstraintViolation<Object>> v = validator.validate(o);
        return v.stream().anyMatch(x -> x.getPropertyPath().toString().contains(fragment));
    }

    @Test void validOrderPasses() { assertTrue(validator.validate(valid()).isEmpty()); }

    @Test void quantityZeroIsRejected() { OrderRequest r = valid(); r.getItems().get(0).setQuantity(0); assertTrue(fails(r, "quantity")); }
    @Test void negativeQuantityIsRejected() { OrderRequest r = valid(); r.getItems().get(0).setQuantity(-3); assertTrue(fails(r, "quantity")); }
    @Test void hugeQuantityIsRejected() { OrderRequest r = valid(); r.getItems().get(0).setQuantity(11); assertTrue(fails(r, "quantity")); }
    @Test void shortPincodeIsRejected() { OrderRequest r = valid(); r.getShippingAddress().setPincode("12"); assertTrue(fails(r, "pincode")); }
    @Test void nonNumericPincodeIsRejected() { OrderRequest r = valid(); r.getShippingAddress().setPincode("ABCDEF"); assertTrue(fails(r, "pincode")); }
    @Test void blankNameIsRejected() { OrderRequest r = valid(); r.getShippingAddress().setName("   "); assertTrue(fails(r, "name")); }
    @Test void blankCityIsRejected() { OrderRequest r = valid(); r.getShippingAddress().setCity(" "); assertTrue(fails(r, "city")); }
    @Test void nonNumericPhoneIsRejected() { OrderRequest r = valid(); r.getShippingAddress().setPhone("abc"); assertTrue(fails(r, "phone")); }
    @Test void emptyItemsRejected() { OrderRequest r = valid(); r.setItems(new ArrayList<>()); assertTrue(fails(r, "items")); }
    @Test void unknownPaymentMethodRejected() { OrderRequest r = valid(); r.setPaymentMethod("cash"); assertTrue(fails(r, "paymentMethod")); }

    @Test
    void cartMergeIsCappedInSize_butItsItemsAreSanitisedNotRejected() {
        CartRequest.MergeRequest m = new CartRequest.MergeRequest();
        m.setItems(new ArrayList<>(java.util.Collections.nCopies(51, new CartRequest.AddItemRequest())));
        assertTrue(fails(m, "items"), "more than 50 lines is rejected");
    }

    @Test
    void emailIsTrimmedBeforeValidation() {
        LoginRequest l = new LoginRequest();
        l.setEmail("  a@b.com  "); l.setPassword("x");
        assertEquals("a@b.com", l.getEmail());
        assertTrue(validator.validate(l).isEmpty());
    }

    @Test
    void registerPasswordBounds() {
        RegisterRequest r = new RegisterRequest();
        r.setName("Sai"); r.setEmail("a@b.com");
        r.setPassword("12345"); assertTrue(fails(r, "password"));
        r.setPassword("x".repeat(73)); assertTrue(fails(r, "password"));
        r.setPassword("123456"); assertFalse(fails(r, "password"));
    }

    @Test
    void productCreateRequiresPositivePrice() {
        ProductRequest p = new ProductRequest();
        p.setName("n"); p.setDescription("d"); p.setImages(List.of("i"));
        assertTrue(fails(p, "price")); // null price
        p.setPrice(-5.0); assertTrue(fails(p, "price"));
        p.setPrice(10.0); assertFalse(fails(p, "price"));
    }
}
