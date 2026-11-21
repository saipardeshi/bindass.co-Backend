package com.bindass.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** PUT /api/auth/profile — every field is optional; only provided fields change. */
@Data
public class UpdateProfileRequest {

    @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
    private String name;

    @Pattern(regexp = "^\\+?[0-9]{10,13}$", message = "Enter a valid phone number (10–13 digits)")
    private String phone;

    @Size(max = 5, message = "You can save at most 5 addresses")
    private List<@Valid AddressRequest> addresses;

    @Data
    public static class AddressRequest {
        @Size(max = 30) private String label;
        @NotBlank(message = "Name is required") @Size(max = 80) private String name;
        @NotBlank(message = "Phone is required")
        @Pattern(regexp = "^\\+?[0-9]{10,13}$", message = "Enter a valid phone number (10–13 digits)")
        private String phone;
        @NotBlank(message = "Address line 1 is required") @Size(max = 200) private String line1;
        @Size(max = 200) private String line2;
        @NotBlank(message = "City is required") @Size(max = 60) private String city;
        @NotBlank(message = "State is required") @Size(max = 60) private String state;
        @Pattern(regexp = "^[1-9][0-9]{5}$", message = "Pincode must be a valid 6-digit number")
        private String pincode;
        private boolean isDefault;
    }
}
