package com.bindass.backend.controller;

import com.bindass.backend.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/newsletter")
@Slf4j
public class NewsletterController {

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(
            @RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || !email.contains("@")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid email"));
        }
        // TODO: save to DB or push to Klaviyo/Mailchimp
        log.info("Newsletter signup: {}", email);
        return ResponseEntity.ok(ApiResponse.ok("Subscribed successfully"));
    }
}