package com.bindass.backend.repository;

import com.bindass.backend.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {

    Optional<Product> findBySlugAndIsPublishedTrue(String slug);

    // Returns List (used for featured/new)
    List<Product> findByIsFeaturedTrueAndIsPublishedTrue(Pageable pageable);
    List<Product> findByIsNewTrueAndIsPublishedTrue(Pageable pageable);

    // Returns Page (used for paginated shop)
    Page<Product> findByIsPublishedTrue(Pageable pageable);

    // Returns Page for filters
    Page<Product> findBySizesInAndIsPublishedTrue(List<String> sizes, Pageable pageable);
    Page<Product> findByPriceBetweenAndIsPublishedTrue(
            double minPrice, double maxPrice, Pageable pageable
    );

    // Returns List for sale filter
    List<Product> findByDiscountGreaterThanAndIsPublishedTrue(int discount, Pageable pageable);

    boolean existsBySlug(String slug);
}