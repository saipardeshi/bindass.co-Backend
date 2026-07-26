package com.bindass.backend.controller;

import com.bindass.backend.dto.request.CartRequest;
import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.dto.response.CartResponse;
import com.bindass.backend.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

// All cart routes require authentication (see SecurityConfig — anyRequest()
// falls through to .authenticated() by default, /api/cart is not in the
// permitAll list). There is intentionally no guest/anonymous cart endpoint
// yet — guest checkout is tracked as a separate piece of work.
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(
                ApiResponse.ok("Cart fetched", cartService.getCart(user.getUsername()))
        );
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody CartRequest.AddItemRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Item added to cart", cartService.addItem(user.getUsername(), request))
        );
    }

    @PutMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> updateItem(
            @AuthenticationPrincipal UserDetails user,
            @Valid @RequestBody CartRequest.UpdateItemRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Cart updated", cartService.updateItem(user.getUsername(), request))
        );
    }

    @DeleteMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @AuthenticationPrincipal UserDetails user,
            @RequestParam String productId,
            @RequestParam String size) {
        return ResponseEntity.ok(
                ApiResponse.ok("Item removed", cartService.removeItem(user.getUsername(), productId, size))
        );
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clearCart(
            @AuthenticationPrincipal UserDetails user) {
        cartService.clearCart(user.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("Cart cleared"));
    }

    // Called once, right after login — merges the guest localStorage cart
    // (sent by the frontend from AuthContext.login/register) into the
    // user's server-side cart.
    @PostMapping("/merge")
    public ResponseEntity<ApiResponse<CartResponse>> mergeGuestCart(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody CartRequest.MergeRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Cart merged", cartService.mergeGuestCart(user.getUsername(), request))
        );
    }
}