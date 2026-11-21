package com.bindass.backend.service;

import com.bindass.backend.dto.request.LoginRequest;
import com.bindass.backend.dto.request.RegisterRequest;
import com.bindass.backend.dto.request.UpdateProfileRequest;
import com.bindass.backend.dto.response.AuthResponse;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ResourceNotFoundException;
import com.bindass.backend.model.Order;
import com.bindass.backend.model.User;
import com.bindass.backend.model.enums.Role;
import com.bindass.backend.repository.CartRepository;
import com.bindass.backend.repository.OrderRepository;
import com.bindass.backend.repository.UserRepository;
import com.bindass.backend.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final int MIN_PASSWORD = 6;
    private static final int MAX_PASSWORD = 72; // bcrypt ignores everything after 72 bytes

    private final UserRepository     userRepository;
    private final CartRepository     cartRepository;
    private final OrderRepository    orderRepository;
    private final PasswordEncoder    passwordEncoder;
    private final JwtTokenProvider   jwtProvider;
    private final AuthenticationManager authManager;
    private final EmailService       emailService;
    private final ProductService     productService;
    private final ReviewService      reviewService;

    @Value("${app.client-url:http://localhost:5173}")
    private String clientUrl;

    // ── Register ─────────────────────────────────────────────
    public AuthResponse register(RegisterRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("Email already registered");
        }

        String rawVerificationToken = UUID.randomUUID().toString();

        User user = User.builder()
                .name(req.getName().trim())
                .email(email)
                .password(passwordEncoder.encode(req.getPassword()))
                .role(Role.USER) // role is NEVER taken from the request
                .isVerified(false)
                .verificationToken(hashToken(rawVerificationToken))
                .verificationTokenExpire(LocalDateTime.now().plusHours(24))
                .build();

        userRepository.save(user);

        // Fire-and-forget welcome & verification emails
        try { emailService.sendWelcomeEmail(user); } catch (Exception ignored) {}
        try {
            emailService.sendVerificationEmail(user, clientUrl + "/verify-email?token=" + rawVerificationToken);
        } catch (Exception ignored) {}

        return buildAuthResponse(user, jwtProvider.generateToken(user.getEmail()));
    }

    // ── Login ────────────────────────────────────────────────
    public AuthResponse login(LoginRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        try {
            authManager.authenticate(new UsernamePasswordAuthenticationToken(email, req.getPassword()));
        } catch (BadCredentialsException | InternalAuthenticationServiceException e) {
            throw new BadRequestException("Invalid email or password");
        }

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return buildAuthResponse(user, jwtProvider.generateToken(user.getEmail()));
    }

    // ── Get current user ─────────────────────────────────────
    public User getMe(String email) {
        return findByEmail(email);
    }

    // ── Update profile (name / phone / saved addresses) ──────
    public User updateProfile(String email, UpdateProfileRequest req) {
        User user = findByEmail(email);

        if (req.getName() != null) user.setName(req.getName().trim());
        if (req.getPhone() != null) user.setPhone(req.getPhone().trim());
        if (req.getAddresses() != null) {
            List<User.Address> addresses = new ArrayList<>();
            boolean defaultSeen = false;
            for (UpdateProfileRequest.AddressRequest a : req.getAddresses()) {
                boolean isDefault = a.isDefault() && !defaultSeen; // at most one default
                defaultSeen |= isDefault;
                addresses.add(User.Address.builder()
                        .label(a.getLabel() != null ? a.getLabel() : "Home")
                        .name(a.getName().trim()).phone(a.getPhone().trim())
                        .line1(a.getLine1().trim()).line2(a.getLine2())
                        .city(a.getCity().trim()).state(a.getState().trim())
                        .pincode(a.getPincode())
                        .isDefault(isDefault)
                        .build());
            }
            user.setAddresses(addresses);
        }
        return userRepository.save(user);
    }

    // ── Toggle wishlist ──────────────────────────────────────
    public Map<String, Object> toggleWishlist(String email, String productId) {
        User user = findByEmail(email);

        List<String> wishlist = user.getWishlist();
        String message;

        if (wishlist.contains(productId)) {
            wishlist.remove(productId); // always allowed (lets users clear stale entries)
            message = "Removed from wishlist";
        } else {
            if (!productService.exists(productId)) {
                throw new ResourceNotFoundException("Product not found");
            }
            if (wishlist.size() >= 200) {
                throw new BadRequestException("Your wishlist is full");
            }
            wishlist.add(productId);
            message = "Added to wishlist";
        }

        userRepository.save(user);
        return Map.of("message", message, "wishlist", wishlist);
    }

    // ── Forgot password ──────────────────────────────────────
    public void forgotPassword(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        // Don't reveal if email exists or not
        userRepository.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            String rawToken = UUID.randomUUID().toString();

            user.setResetPasswordToken(hashToken(rawToken));
            user.setResetPasswordExpire(LocalDateTime.now().plusMinutes(15));
            userRepository.save(user);

            try { emailService.sendPasswordResetEmail(user, clientUrl + "/reset-password/" + rawToken); }
            catch (Exception ignored) {}
        });
    }

    // ── Reset password ───────────────────────────────────────
    public AuthResponse resetPassword(String rawToken, String newPassword) {
        // validate first (a null/short password used to reach the encoder and 500, or bypass the 6-char rule)
        validatePassword(newPassword);

        User user = userRepository.findByResetPasswordToken(hashToken(rawToken))
                .orElseThrow(() -> new BadRequestException("Invalid or expired reset token"));

        if (user.getResetPasswordExpire() == null || user.getResetPasswordExpire().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Reset token has expired");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setResetPasswordToken(null);
        user.setResetPasswordExpire(null);
        userRepository.save(user);

        return buildAuthResponse(user, jwtProvider.generateToken(user.getEmail()));
    }

    // ── Verify Email ─────────────────────────────────────────
    public void verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadRequestException("Verification token is required");
        }
        User user = userRepository.findByVerificationToken(hashToken(rawToken.trim()))
                .orElseThrow(() -> new BadRequestException("Invalid or expired verification token"));

        if (user.getVerificationTokenExpire() != null && user.getVerificationTokenExpire().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Verification token has expired. Please request a new verification email.");
        }

        user.setVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpire(null);
        userRepository.save(user);
    }

    // ── Resend Verification Email ────────────────────────────
    public void resendVerification(String email) {
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Email is required");
        }
        userRepository.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            if (user.isVerified()) return;

            String rawToken = UUID.randomUUID().toString();
            user.setVerificationToken(hashToken(rawToken));
            user.setVerificationTokenExpire(LocalDateTime.now().plusHours(24));
            userRepository.save(user);

            try { emailService.sendVerificationEmail(user, clientUrl + "/verify-email?token=" + rawToken); }
            catch (Exception ignored) {}
        });
    }

    // ── Delete Account ───────────────────────────────────────
    public void deleteAccount(String email) {
        User user = findByEmail(email);

        try {
            cartRepository.deleteByUserId(user.getId());
        } catch (Exception e) {
            log.error("Failed to delete cart for user {}: {}", user.getId(), e.getMessage());
        }

        // The user's reviews are personal content — remove them and fix product ratings
        try {
            reviewService.deleteAllByUser(user.getId());
        } catch (Exception e) {
            log.warn("Could not delete reviews during account deletion: {}", e.getMessage());
        }

        // Anonymize orders (kept for accounting) — also detaches them from the user id
        try {
            List<Order> orders = orderRepository.findByUserId(user.getId());
            for (Order order : orders) {
                if (order.getShippingAddress() != null) {
                    order.getShippingAddress().setName("Account Deleted");
                    order.getShippingAddress().setPhone("0000000000");
                    order.getShippingAddress().setLine1("Redacted");
                    order.getShippingAddress().setLine2(null);
                }
                orderRepository.save(order);
            }
        } catch (Exception e) {
            log.warn("Could not anonymize orders during account deletion: {}", e.getMessage());
        }

        userRepository.delete(user);
        log.info("User account (ID: {}) permanently deleted", user.getId());
    }

    // ── Helpers ──────────────────────────────────────────────
    private User findByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email != null ? email.trim() : "")
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new BadRequestException("Password must be " + MIN_PASSWORD + "-" + MAX_PASSWORD + " characters");
        }
    }

    private AuthResponse buildAuthResponse(User user, String token) {
        return AuthResponse.builder()
                .token(token)
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .isVerified(user.isVerified())
                .build();
    }

    private String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Token hashing failed");
        }
    }
}
