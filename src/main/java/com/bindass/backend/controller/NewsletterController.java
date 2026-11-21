package com.bindass.backend.controller;

import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.model.NewsletterSubscriber;
import com.bindass.backend.repository.NewsletterRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/api/newsletter")
@RequiredArgsConstructor
@Slf4j
public class NewsletterController {

    private final NewsletterRepository newsletterRepository;

    @Data
    public static class SubscribeRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email")
        @Size(max = 254, message = "Invalid email")
        private String email;

        public void setEmail(String email) {
            this.email = email == null ? null : email.trim();
        }
    }

    // POST /api/newsletter/subscribe — validated, stored (previously only logged), idempotent
    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(@Valid @RequestBody SubscribeRequest body) {
        String email = body.getEmail().toLowerCase(Locale.ROOT);
        if (!newsletterRepository.existsByEmail(email)) {
            try {
                newsletterRepository.save(NewsletterSubscriber.builder().email(email).build());
            } catch (DuplicateKeyException ignored) {
                // concurrent duplicate signup — same result as already subscribed
            }
        }
        return ResponseEntity.ok(ApiResponse.ok("Subscribed successfully"));
    }
}
