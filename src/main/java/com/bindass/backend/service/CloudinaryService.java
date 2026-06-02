package com.bindass.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudinaryService {

    private final Cloudinary cloudinary;

    // ── Upload single image ───────────────────────────────────
    @SuppressWarnings("unchecked")
    public Map<String, String> uploadImage(MultipartFile file) throws IOException {
        Map<?, ?> result = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "folder",          "bindass/products",
                        "transformation",  "q_auto,f_auto,w_1200,c_limit",
                        "use_filename",    false,
                        "unique_filename", true
                )
        );

        return Map.of(
                "url",      result.get("secure_url").toString(),
                "publicId", result.get("public_id").toString()
        );
    }

    // ── Upload multiple images ────────────────────────────────
    public List<Map<String, String>> uploadImages(List<MultipartFile> files) {
        List<Map<String, String>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                results.add(uploadImage(file));
            } catch (IOException e) {
                log.error("Failed to upload image: {}", e.getMessage());
            }
        }
        return results;
    }

    // ── Delete image by public ID ─────────────────────────────
    public boolean deleteImage(String publicId) {
        try {
            Map<?, ?> result = cloudinary.uploader()
                    .destroy(publicId, ObjectUtils.emptyMap());
            return "ok".equals(result.get("result"));
        } catch (IOException e) {
            log.error("Failed to delete image {}: {}", publicId, e.getMessage());
            return false;
        }
    }
}