package com.bindass.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory sliding-window rate limiting for sensitive public endpoints.
 *
 * SECURITY: the client IP is taken from the TCP connection. The X-Forwarded-For /
 * X-Real-IP headers are only honoured when {@code app.rate-limit.trust-proxy=true}
 * (i.e. you run behind a reverse proxy that overwrites them). Trusting them
 * unconditionally lets an attacker rotate a fake header and never get limited.
 */
@Component
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> requestCounts = new ConcurrentHashMap<>();

    private final boolean trustProxy;

    public RateLimitingFilter(@Value("${app.rate-limit.trust-proxy:false}") boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    private record LimitRule(int maxRequests, long windowMillis) {}

    private static final LimitRule LOGIN_RULE = new LimitRule(10, 60_000L);               // 10 / min
    private static final LimitRule REGISTER_RULE = new LimitRule(5, 60_000L);             // 5 / min
    private static final LimitRule FORGOT_PASSWORD_RULE = new LimitRule(3, 15 * 60_000L); // 3 / 15 min
    private static final LimitRule RESEND_VERIFY_RULE = new LimitRule(3, 15 * 60_000L);   // 3 / 15 min
    private static final LimitRule RESET_PASSWORD_RULE = new LimitRule(10, 15 * 60_000L); // 10 / 15 min
    private static final LimitRule NEWSLETTER_RULE = new LimitRule(5, 60_000L);           // 5 / min

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        if ("POST".equalsIgnoreCase(request.getMethod())) {
            LimitRule rule = null;
            String action = null;

            if (path.endsWith("/api/auth/login")) {
                rule = LOGIN_RULE; action = "LOGIN";
            } else if (path.endsWith("/api/auth/register")) {
                rule = REGISTER_RULE; action = "REGISTER";
            } else if (path.endsWith("/api/auth/forgot-password")) {
                rule = FORGOT_PASSWORD_RULE; action = "FORGOT_PASSWORD";
            } else if (path.endsWith("/api/auth/resend-verification")) {
                rule = RESEND_VERIFY_RULE; action = "RESEND_VERIFY";
            } else if (path.contains("/api/auth/reset-password/")) {
                rule = RESET_PASSWORD_RULE; action = "RESET_PASSWORD";
            } else if (path.endsWith("/api/newsletter/subscribe")) {
                rule = NEWSLETTER_RULE; action = "NEWSLETTER";
            }

            if (rule != null) {
                String clientIp = extractClientIp(request);
                if (!isAllowed(clientIp + ":" + action, rule)) {
                    log.warn("Rate limit exceeded for IP: {} on action: {}", clientIp, action);
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setContentType("application/json");
                    response.setHeader("Retry-After", String.valueOf(rule.windowMillis() / 1000));
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

        ConcurrentLinkedQueue<Long> timestamps = requestCounts.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());

        // check-and-add must be atomic per key, otherwise concurrent requests can overshoot the limit
        synchronized (timestamps) {
            Long head;
            while ((head = timestamps.peek()) != null && head < windowStart) {
                timestamps.poll();
            }
            if (timestamps.size() >= rule.maxRequests()) {
                return false;
            }
            timestamps.add(now);
        }

        // self-cleaning if the map grows too large
        if (requestCounts.size() > 10_000) {
            requestCounts.entrySet().removeIf(entry -> {
                Long newest = entry.getValue().peek();
                return newest == null || newest < now - 3_600_000L;
            });
        }
        return true;
    }

    private String extractClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xf = request.getHeader("X-Forwarded-For");
            if (xf != null && !xf.isBlank()) {
                return xf.split(",")[0].trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
