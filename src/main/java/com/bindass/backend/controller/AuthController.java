package com.bindass.backend.controller;

import com.bindass.backend.dto.request.LoginRequest;
import com.bindass.backend.dto.request.RegisterRequest;
import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.dto.response.AuthResponse;
import com.bindass.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // POST /api/auth/register
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created successfully", response));
    }

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Logged in successfully", response));
    }

    // GET /api/auth/me — requires JWT
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Object>> getMe(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            throw new com.bindass.backend.exception.UnauthorizedException("Please sign in to continue");
        }
        Object user = authService.getMe(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("User fetched", user));
    }

    // DELETE /api/auth/me — delete user's own account permanently
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteMe(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            throw new com.bindass.backend.exception.UnauthorizedException("Please sign in to continue");
        }
        authService.deleteAccount(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("Your account has been deleted permanently"));
    }

    // PUT /api/auth/wishlist/{productId}
    @PutMapping("/wishlist/{productId}")
    public ResponseEntity<ApiResponse<Object>> toggleWishlist(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String productId) {
        if (userDetails == null) {
            throw new com.bindass.backend.exception.UnauthorizedException("Please sign in to continue");
        }
        Object result = authService.toggleWishlist(userDetails.getUsername(), productId);
        return ResponseEntity.ok(ApiResponse.ok("Wishlist updated", result));
    }

    // POST /api/auth/forgot-password
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @RequestBody java.util.Map<String, String> body) {
        authService.forgotPassword(body.get("email"));
        return ResponseEntity.ok(ApiResponse.ok("If that email exists, a reset link was sent."));
    }

    // POST /api/auth/reset-password/{token}
    @PostMapping("/reset-password/{token}")
    public ResponseEntity<ApiResponse<AuthResponse>> resetPassword(
            @PathVariable String token,
            @RequestBody java.util.Map<String, String> body) {
        AuthResponse response = authService.resetPassword(token, body.get("password"));
        return ResponseEntity.ok(ApiResponse.ok("Password reset successful", response));
    }

    // POST /api/auth/verify-email
    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestParam(required = false) String token) {
        String verificationToken = token != null ? token : (body != null ? body.get("token") : null);
        authService.verifyEmail(verificationToken);
        return ResponseEntity.ok(ApiResponse.ok("Email verified successfully"));
    }

    // POST /api/auth/resend-verification
    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String email = (userDetails != null) ? userDetails.getUsername() : (body != null ? body.get("email") : null);
        authService.resendVerification(email);
        return ResponseEntity.ok(ApiResponse.ok("If your account exists and is unverified, a new link has been sent."));
    }
}