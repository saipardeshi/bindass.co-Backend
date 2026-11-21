package com.bindass.backend.config;

import com.bindass.backend.model.Product;
import com.bindass.backend.model.User;
import com.bindass.backend.model.enums.Role;
import com.bindass.backend.repository.ProductRepository;
import com.bindass.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * Opt-in startup data:
 *
 *  1. app.seed.enabled=true      → inserts the starter catalog (seed/products.json) for any slug
 *                                  that doesn't exist yet. Idempotent; never overwrites existing products
 *                                  (except repairing the legacy demo product's missing images).
 *  2. app.admin.email/password   → creates the FIRST admin if no ADMIN exists yet. There was previously
 *                                  no way to obtain an admin account at all.
 *
 * Both are OFF unless configured, so production data is never touched by accident.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final UserRepository    userRepository;
    private final PasswordEncoder   passwordEncoder;
    private final ObjectMapper      objectMapper;

    @Value("${app.seed.enabled:false}")
    private boolean seedEnabled;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(String... args) throws Exception {
        if (seedEnabled) seedProducts();
        bootstrapAdmin();
    }

    private void seedProducts() throws Exception {
        List<Product> catalog = new ArrayList<>();
        try (InputStream in = new ClassPathResource("seed/products.json").getInputStream()) {
            for (JsonNode n : objectMapper.readTree(in)) {
                String slug = n.get("slug").asText();
                if (productRepository.existsBySlug(slug)) continue;

                Map<String, Integer> stock = new LinkedHashMap<>();
                n.get("stock").fields().forEachRemaining(e -> stock.put(e.getKey(), e.getValue().asInt()));

                catalog.add(Product.builder()
                        .name(n.get("name").asText())
                        .slug(slug)
                        .description(n.get("description").asText())
                        .price(n.get("price").asDouble())
                        .originalPrice(n.hasNonNull("originalPrice") ? n.get("originalPrice").asDouble() : null)
                        .discount(n.get("discount").asInt())
                        .images(strings(n.get("images")))
                        .category(n.get("category").asText())
                        .sizes(strings(n.get("sizes")))
                        .stock(stock)
                        .details(strings(n.get("details")))
                        .care(strings(n.get("care")))
                        .tags(strings(n.get("tags")))
                        .isFeatured(n.get("featured").asBoolean())
                        .isNew(n.get("isNew").asBoolean())
                        .totalSold(n.get("totalSold").asInt())
                        .build());
            }
        }
        if (!catalog.isEmpty()) {
            productRepository.saveAll(catalog);
            log.info("Seeded {} catalog products", catalog.size());
        }

        // The original demo product pointed at Cloudinary ids that don't exist (broken images) — repair it.
        productRepository.findBySlugAndIsPublishedTrue("shadow-oversized-hoodie").ifPresent(p -> {
            boolean legacy = p.getImages() != null && p.getImages().stream()
                    .anyMatch(i -> i.startsWith("bindass/products/shadow-hoodie"));
            if (legacy) {
                p.setImages(new ArrayList<>(List.of(
                        "04fa1b239c87c925221839f4fdd14fbe_jpc0xg",
                        "e2ef98fba55320fac63538b3e27132cb_al66lw")));
                productRepository.save(p);
                log.info("Repaired images of legacy demo product {}", p.getSlug());
            }
        });
    }

    private void bootstrapAdmin() {
        if (adminEmail.isBlank() || adminPassword.isBlank()) return;
        if (userRepository.countByRole(Role.ADMIN) > 0) return;
        if (adminPassword.length() < 10) {
            log.warn("app.admin.password is shorter than 10 characters — admin NOT created");
            return;
        }
        String email = adminEmail.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            log.warn("app.admin.email {} already exists as a normal user — promote it manually", email);
            return;
        }
        userRepository.save(User.builder()
                .name("Admin")
                .email(email)
                .password(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .isVerified(true)
                .build());
        log.info("Bootstrap admin account created for {}", email);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }
}
