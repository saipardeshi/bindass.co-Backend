package com.bindass.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * High-performance, in-memory rate limiting filter for sensitive authentication endpoints.
 * Protects against brute-force credential stuffing and email flooding.
 */
@Component
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    // Tracks request timestamps per key (e.g. "IP:LOGIN")
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> requestCounts = new ConcurrentHashMap<>();

    // Rate limit configuration records
    private record LimitRule(int maxRequests, long windowMillis) {}

    private static final LimitRule LOGIN_RULE = new LimitRule(10, 60_000L);            // 10 requests / 1 min
    private static final LimitRule REGISTER_RULE = new LimitRule(5, 60_000L);           // 5 requests / 1 min
    private static final LimitRule FORGOT_PASSWORD_RULE = new LimitRule(3, 15 * 60_000L); // 3 requests / 15 mins
    private static final LimitRule RESEND_VERIFY_RULE = new LimitRule(3, 15 * 60_000L);  // 3 requests / 15 mins

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Only enforce on POST auth endpoints
        if ("POST".equalsIgnoreCase(method)) {
            LimitRule rule = null;
            String action = null;

            if (path.endsWith("/api/auth/login")) {
                rule = LOGIN_RULE;
                action = "LOGIN";
            } else if (path.endsWith("/api/auth/register")) {
                rule = REGISTER_RULE;
                action = "REGISTER";
            } else if (path.endsWith("/api/auth/forgot-password")) {
                rule = FORGOT_PASSWORD_RULE;
                action = "FORGOT_PASSWORD";
            } else if (path.endsWith("/api/auth/resend-verification")) {
                rule = RESEND_VERIFY_RULE;
                action = "RESEND_VERIFY";
            }

            if (rule != null) {
                String clientIp = extractClientIp(request);
                String rateKey = clientIp + ":" + action;

                if (!isAllowed(rateKey, rule)) {
                    log.warn("Rate limit exceeded for IP: {} on action: {}", clientIp, action);
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setContentType("application/json");
                    response.setHeader("Retry-After", "60");
                    response.getWriter().write(
                            "{\"success\":false,\"message\":\"Too many requests. Please wait a moment before trying again.\"}"
                    );
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String key, LimitRule rule) {
        long now = System.currentTimeMillis();
        long windowStart = now - rule.windowMillis();

        ConcurrentLinkedQueue<Long> timestamps = requestCounts.computeIfAbsent(
                key, k -> new ConcurrentLinkedQueue<>()
        );

        // Remove timestamps outside the sliding window
        while (!timestamps.isEmpty()) {
            Long head = timestamps.peek();
            if (head != null && head < windowStart) {
                timestamps.poll();
            } else {
                break;
            }
        }

        if (timestamps.size() >= rule.maxRequests()) {
            return false;
        }

        timestamps.add(now);

        // Self-cleaning if map grows too large (> 10,000 entries)
        if (requestCounts.size() > 10_000) {
            requestCounts.entrySet().removeIf(entry -> {
                ConcurrentLinkedQueue<Long> queue = entry.getValue();
                return queue.isEmpty() || (queue.peek() != null && queue.peek() < now - 3600_000L);
            });
        }

        return true;
    }

    private String extractClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
