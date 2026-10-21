package com.bindass.backend.dto.response;

import com.bindass.backend.model.enums.Role;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String id;
    private String name;
    private String email;
    private Role role;

    @JsonProperty("isVerified")
    private boolean isVerified;
}