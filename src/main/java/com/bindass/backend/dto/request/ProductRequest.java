package com.bindass.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Used for both create (POST, @Valid) and update (PUT, partial). Wrapper types are
 * deliberate: `null` means "not provided", so a partial update no longer silently
 * resets featured/new to false or ignores fields.
 */
@Data
public class ProductRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 120, message = "Name is too long")
    private String name;

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be positive")
    private Double price;

    @Positive(message = "Original price must be positive")
    private Double originalPrice;

    @Min(value = 0, message = "Discount cannot be negative")
    @Max(value = 100, message = "Discount cannot exceed 100")
    private Integer discount;

    @NotEmpty(message = "At least one image is required")
    private List<String> images;

    private List<String> sizes;
    private Map<String, Integer> stock;
    private List<String> details;
    private List<String> care;
    private List<String> tags;
    private String category;
    private String material;
    private String weight;
    private Boolean featured;

    // "isNew" is awkward in JSON/bean naming — kept as newProduct for API compatibility
    private Boolean newProduct;
    private Boolean published;
}
