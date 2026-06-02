package com.bindass.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ReviewRequest {
    @Min(1) @Max(5)
    private int rating;
    @Size(max = 100)  private String title;
    @Size(max = 1000) private String comment;
}