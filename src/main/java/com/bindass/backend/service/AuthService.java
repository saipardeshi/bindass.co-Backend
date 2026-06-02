package com.bindass.backend.service;

import com.bindass.backend.dto.request.LoginRequest;
import com.bindass.backend.dto.request.RegisterRequest;
import com.bindass.backend.dto.response.AuthResponse;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ResourceNotFoundException;
import com.bindass.backend.model.User;
import com.bindass.backend.model.enums.Role;
import com.bindass.backend.repository.UserRepository;
import com.bindass.backend.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository     userRepository;
    private final PasswordEncoder    passwordEncoder;
    private final JwtTokenProvider   jwtProvider;
    private final AuthenticationManager authManager;
    private final EmailService       emailService;

    // ── Register ─────────────────────────────────────────────
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new BadRequestException("Email already registered");
        }

        User user = User.builder()
                .name(req.getName())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(Role.USER)
                .build();

        userRepository.save(user);

        // Fire-and-forget welcome email
        try { emailService.sendWelcomeEmail(user); } catch (Exception ignored) {}

        String token = jwtProvider.generateToken(user.getEmail());
        return buildAuthResponse(user, token);
    }

    // ── Login ────────────────────────────────────────────────
    public AuthResponse login(LoginRequest req) {
        try {
            // This triggers Spring Security to verify credentials
            authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword())
            );
        } catch (BadCredentialsException e) {
            throw new BadRequestException("Invalid email or password");
        }

        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String token = jwtProvider.generateToken(user.getEmail());
        return buildAuthResponse(user, token);
    }

    // ── Get current user ─────────────────────────────────────
    public User getMe(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    // ── Toggle wishlist ──────────────────────────────────────
    public Map<String, Object> toggleWishlist(String email, String productId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<String> wishlist = user.getWishlist();
        String message;

        if (wishlist.contains(productId)) {
            wishlist.remove(productId);
            message = "Removed from wishlist";
        } else {
            wishlist.add(productId);
            message = "Added to wishlist";
        }

        userRepository.save(user);
        return Map.of("message", message, "wishlist", wishlist);
    }

    // ── Forgot password ──────────────────────────────────────
    public void forgotPassword(String email) {
        // Don't reveal if email exists or not
        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken    = UUID.randomUUID().toString();
            String hashedToken = hashToken(rawToken);

            user.setResetPasswordToken(hashedToken);
            user.setResetPasswordExpire(LocalDateTime.now().plusMinutes(15));
            userRepository.save(user);

            String resetUrl = "http://localhost:5173/reset-password/" + rawToken;
            try { emailService.sendPasswordResetEmail(user, resetUrl); }
            catch (Exception ignored) {}
        });
    }

    // ── Reset password ───────────────────────────────────────
    public AuthResponse resetPassword(String rawToken, String newPassword) {
        String hashed = hashToken(rawToken);

        User user = userRepository.findByResetPasswordToken(hashed)
                .orElseThrow(() -> new BadRequestException("Invalid or expired reset token"));

        if (user.getResetPasswordExpire().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Reset token has expired");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setResetPasswordToken(null);
        user.setResetPasswordExpire(null);
        userRepository.save(user);

        String token = jwtProvider.generateToken(user.getEmail());
        return buildAuthResponse(user, token);
    }

    // ── Helpers ──────────────────────────────────────────────
    private AuthResponse buildAuthResponse(User user, String token) {
        return AuthResponse.builder()
                .token(token)
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
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