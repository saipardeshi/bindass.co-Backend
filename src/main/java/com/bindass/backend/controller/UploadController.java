package com.bindass.backend.controller;

import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UploadController {

    private final CloudinaryService cloudinaryService;

    // POST /api/upload/images (multipart form-data, field: "images")
    @PostMapping("/images")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> uploadImages(
            @RequestParam("images") List<MultipartFile> files) {
        List<Map<String, String>> results = cloudinaryService.uploadImages(files);
        return ResponseEntity.ok(ApiResponse.ok("Images uploaded", results));
    }

    // DELETE /api/upload/image
    @DeleteMapping("/image")
    public ResponseEntity<ApiResponse<Void>> deleteImage(
            @RequestBody Map<String, String> body) {
        cloudinaryService.deleteImage(body.get("publicId"));
        return ResponseEntity.ok(ApiResponse.ok("Image deleted"));
    }
}