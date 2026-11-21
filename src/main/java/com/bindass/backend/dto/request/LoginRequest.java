package com.bindass.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    // trim BEFORE @Email validation runs (stray spaces used to be rejected as "invalid email")
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }
}
