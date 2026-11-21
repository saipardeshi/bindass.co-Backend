package com.bindass.backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitingFilterTest {

    private int hit(RateLimitingFilter f, String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) req.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (a, b) -> {};
        f.doFilter(req, res, chain);
        return res.getStatus();
    }

    @Test
    void limitsLoginAfterTenAttemptsPerIp() throws Exception {
        RateLimitingFilter f = new RateLimitingFilter(false);
        for (int i = 0; i < 10; i++) assertEquals(200, hit(f, "1.2.3.4", null));
        assertEquals(429, hit(f, "1.2.3.4", null));
    }

    @Test
    void spoofedXForwardedForCannotBypassTheLimit() throws Exception {
        RateLimitingFilter f = new RateLimitingFilter(false); // default: do NOT trust the header
        int blocked = 0;
        for (int i = 0; i < 15; i++) if (hit(f, "9.9.9.9", "10.0." + i + ".1") == 429) blocked++;
        assertTrue(blocked >= 5, "rotating a fake X-Forwarded-For must not evade the limiter, blocked=" + blocked);
    }

    @Test
    void forwardedHeaderIsHonouredOnlyWhenBehindATrustedProxy() throws Exception {
        RateLimitingFilter f = new RateLimitingFilter(true);
        // same proxy address, different real clients → separate buckets
        for (int i = 0; i < 12; i++) assertEquals(200, hit(f, "10.0.0.1", "203.0.113." + i));
    }

    @Test
    void otherEndpointsAreNotLimited() throws Exception {
        RateLimitingFilter f = new RateLimitingFilter(false);
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/products");
            req.setRemoteAddr("1.2.3.4");
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req, res, (a, b) -> {});
            assertEquals(200, res.getStatus());
        }
    }
}
