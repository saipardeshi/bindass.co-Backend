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
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;
}