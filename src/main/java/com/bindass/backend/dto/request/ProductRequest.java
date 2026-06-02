package com.bindass.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ProductRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Description is required")
    private String description;

    @Positive(message = "Price must be positive")
    private double price;

    private Double originalPrice;
    private int discount;

    @NotEmpty(message = "At least one image is required")
    private List<String> images;

    private List<String> sizes;
    private Map<String, Integer> stock;
    private List<String> details;
    private List<String> care;
    private List<String> tags;
    private String material;
    private String weight;
    private boolean featured;

    // "isNew" is a reserved word in some contexts — use isNewProduct
    private boolean newProduct;
}