package com.bindass.backend.controller;

import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.service.AdminService;
import com.bindass.backend.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')") // All routes in this controller require ADMIN
public class AdminController {

    private final AdminService  adminService;
    private final ReviewService reviewService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard() {
        return ResponseEntity.ok(
                ApiResponse.ok("Dashboard stats", adminService.getDashboardStats())
        );
    }

    @GetMapping("/revenue-chart")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> revenueChart() {
        return ResponseEntity.ok(
                ApiResponse.ok("Revenue chart", adminService.getRevenueChart())
        );
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toggleRole(
            @PathVariable String id) {
        return ResponseEntity.ok(
                ApiResponse.ok("Role updated", adminService.toggleUserRole(id))
        );
    }

    @DeleteMapping("/reviews/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteReview(@PathVariable String id) {
        reviewService.adminDeleteReview(id);
        return ResponseEntity.ok(ApiResponse.ok("Review removed"));
    }
}