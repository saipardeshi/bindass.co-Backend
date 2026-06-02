package com.bindass.backend.service;

import com.bindass.backend.dto.request.ProductRequest;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ResourceNotFoundException;
import com.bindass.backend.model.Product;
import com.bindass.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    public Page<Product> getProducts(
            int page, int size, String sort,
            Double minPrice, Double maxPrice,
            List<String> sizes, boolean sale
    ) {
        Sort sortObj = switch (sort) {
            case "price_asc"  -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "popular"    -> Sort.by("totalSold").descending();
            case "rating"     -> Sort.by("averageRating").descending();
            default           -> Sort.by("createdAt").descending();
        };

        Pageable pageable = PageRequest.of(page, size, sortObj);

        if (minPrice != null && maxPrice != null) {
            return productRepository.findByPriceBetweenAndIsPublishedTrue(
                    minPrice, maxPrice, pageable
            );
        }

        if (sizes != null && !sizes.isEmpty()) {
            return productRepository.findBySizesInAndIsPublishedTrue(sizes, pageable);
        }

        if (sale) {
            List<Product> saleList = productRepository
                    .findByDiscountGreaterThanAndIsPublishedTrue(0, pageable);
            return new PageImpl<>(saleList, pageable, saleList.size());
        }

        return productRepository.findByIsPublishedTrue(pageable);
    }

    public Product getBySlug(String slug) {
        return productRepository.findBySlugAndIsPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + slug));
    }

    public List<Product> getFeatured() {
        Pageable p = PageRequest.of(0, 8, Sort.by("createdAt").descending());
        return productRepository.findByIsFeaturedTrueAndIsPublishedTrue(p);
    }

    public List<Product> getNewArrivals() {
        Pageable p = PageRequest.of(0, 8, Sort.by("createdAt").descending());
        return productRepository.findByIsNewTrueAndIsPublishedTrue(p);
    }

    public List<Product> getBestSellers() {
        Pageable p = PageRequest.of(0, 8, Sort.by("totalSold").descending());
        return productRepository.findByIsPublishedTrue(p).stream().toList();
    }

    public Product createProduct(ProductRequest req) {
        String slug = req.getName().toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-");

        if (productRepository.existsBySlug(slug)) {
            slug = slug + "-" + System.currentTimeMillis();
        }

        Product product = Product.builder()
                .name(req.getName())
                .slug(slug)
                .description(req.getDescription())
                .price(req.getPrice())
                .originalPrice(req.getOriginalPrice())
                .discount(req.getDiscount())
                .images(req.getImages())
                .sizes(req.getSizes())
                .stock(req.getStock())
                .details(req.getDetails())
                .care(req.getCare())
                .tags(req.getTags())
                .material(req.getMaterial())
                .weight(req.getWeight())
                .isFeatured(req.isFeatured())
                .isNew(req.isNewProduct())
                .build();

        return productRepository.save(product);
    }

    public Product updateProduct(String id, ProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        if (req.getName()        != null) product.setName(req.getName());
        if (req.getDescription() != null) product.setDescription(req.getDescription());
        if (req.getPrice()       > 0)     product.setPrice(req.getPrice());
        if (req.getImages()      != null) product.setImages(req.getImages());
        if (req.getSizes()       != null) product.setSizes(req.getSizes());
        if (req.getStock()       != null) product.setStock(req.getStock());
        if (req.getTags()        != null) product.setTags(req.getTags());
        product.setFeatured(req.isFeatured());
        product.setNew(req.isNewProduct());

        return productRepository.save(product);
    }

    public void deleteProduct(String id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        productRepository.delete(product);
    }

    public void decrementStock(String productId, String size, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        Map<String, Integer> stock = product.getStock();
        int current = stock.getOrDefault(size, 0);

        if (current < quantity) {
            throw new BadRequestException(
                    product.getName() + " (" + size + ") — only " + current + " left in stock"
            );
        }

        stock.put(size, current - quantity);
        product.setTotalSold(product.getTotalSold() + quantity);
        boolean allOut = stock.values().stream().allMatch(v -> v <= 0);
        product.setSoldOut(allOut);
        productRepository.save(product);
    }

    public void incrementStock(String productId, String size, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        Map<String, Integer> stock = product.getStock();
        stock.put(size, stock.getOrDefault(size, 0) + quantity);
        product.setTotalSold(Math.max(0, product.getTotalSold() - quantity));
        product.setSoldOut(false);
        productRepository.save(product);
    }

    public void updateRating(String productId, double avgRating, int numReviews) {
        productRepository.findById(productId).ifPresent(p -> {
            p.setAverageRating(avgRating);
            p.setNumReviews(numReviews);
            productRepository.save(p);
        });
    }
}