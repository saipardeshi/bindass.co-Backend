package com.bindass.backend.controller;

import com.bindass.backend.dto.request.ProductRequest;
import com.bindass.backend.dto.response.ApiResponse;
import com.bindass.backend.model.Product;
import com.bindass.backend.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // GET /api/products?page=0&size=12&sort=newest&minPrice=&maxPrice=&sale=false
    @GetMapping
    public ResponseEntity<ApiResponse<Page<Product>>> getProducts(
            @RequestParam(defaultValue = "0")     int page,
            @RequestParam(defaultValue = "12")    int size,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(required = false)       Double minPrice,
            @RequestParam(required = false)       Double maxPrice,
            @RequestParam(required = false)       List<String> sizes,
            @RequestParam(defaultValue = "false") boolean sale
    ) {
        Page<Product> products = productService.getProducts(
                page, size, sort, minPrice, maxPrice, sizes, sale
        );
        return ResponseEntity.ok(ApiResponse.ok("Products fetched", products));
    }

    @GetMapping("/featured")
    public ResponseEntity<ApiResponse<List<Product>>> getFeatured() {
        return ResponseEntity.ok(
                ApiResponse.ok("Featured products", productService.getFeatured())
        );
    }

    @GetMapping("/new-arrivals")
    public ResponseEntity<ApiResponse<List<Product>>> getNewArrivals() {
        return ResponseEntity.ok(
                ApiResponse.ok("New arrivals", productService.getNewArrivals())
        );
    }

    @GetMapping("/best-sellers")
    public ResponseEntity<ApiResponse<List<Product>>> getBestSellers() {
        return ResponseEntity.ok(
                ApiResponse.ok("Best sellers", productService.getBestSellers())
        );
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResponse<Product>> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(
                ApiResponse.ok("Product fetched", productService.getBySlug(slug))
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Product>> create(
            @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Product created", productService.createProduct(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Product>> update(
            @PathVariable String id,
            @RequestBody ProductRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Product updated", productService.updateProduct(id, request))
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(ApiResponse.ok("Product deleted"));
    }
}