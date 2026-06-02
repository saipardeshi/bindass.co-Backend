package com.bindass.backend.model;

import com.bindass.backend.model.enums.Role;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// @Document maps this class to the "users" collection in MongoDB
@Document(collection = "users")
@Data               // Lombok: generates getters, setters, toString, equals, hashCode
@NoArgsConstructor  // Lombok: generates no-args constructor
@AllArgsConstructor // Lombok: generates all-args constructor
@Builder            // Lombok: enables User.builder().name("x").build() pattern
public class User {

    @Id
    private String id; // MongoDB uses String IDs (ObjectId as string)

    private String name;

    @Indexed(unique = true) // Creates a unique index on email field in MongoDB
    private String email;

    private String password; // Stored as bcrypt hash — NEVER plain text

    @Builder.Default
    private Role role = Role.USER; // Default role is USER

    private String phone;
    private String avatar;

    // List of product IDs the user has wishlisted
    @Builder.Default
    private List<String> wishlist = new ArrayList<>();

    // Embedded address list (stored inside the user document)
    @Builder.Default
    private List<Address> addresses = new ArrayList<>();

    private boolean isVerified;

    // For password reset flow
    private String resetPasswordToken;
    private LocalDateTime resetPasswordExpire;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // ── Inner class: Address (embedded, not a separate collection) ──
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Address {
        @Builder.Default
        private String label = "Home";
        private String name;
        private String phone;
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String pincode;
        @Builder.Default
        private String country = "India";
        @Builder.Default
        private boolean isDefault = false;
    }
}