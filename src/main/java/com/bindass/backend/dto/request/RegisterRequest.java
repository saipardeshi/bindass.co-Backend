package com.bindass.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

// Data Transfer Object — what the client sends to /api/auth/register
@Data // Lombok: getters + setters
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, max = 72, message = "Password must be 6-72 characters")
    private String password;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }
}
