package com.bindass.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

// Standard API response wrapper — all endpoints return this
// { "success": true, "message": "...", "data": {...} }
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // Don't include null fields in JSON
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data; // Generic — can hold any type (User, Product, List, etc.)

    // Quick factory methods
    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder().success(true).message(message).data(data).build();
    }
    public static <T> ApiResponse<T> ok(String message) {
        return ApiResponse.<T>builder().success(true).message(message).build();
    }
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder().success(false).message(message).build();
    }
}