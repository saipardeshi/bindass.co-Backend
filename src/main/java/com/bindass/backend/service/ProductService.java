package com.bindass.backend.service;

import com.bindass.backend.dto.request.ProductRequest;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ResourceNotFoundException;
import com.bindass.backend.model.Product;
import com.bindass.backend.repository.ProductRepository;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductService {

    public static final int MAX_PAGE_SIZE = 50;
    private static final Pattern SIZE_KEY = Pattern.compile("^[A-Za-z0-9]{1,10}$");
    private static final List<String> DEFAULT_SIZES = List.of("S", "M", "L", "XL");

    private final ProductRepository productRepository;
    private final MongoTemplate mongoTemplate;

    // ── Listing / search (all filters combine) ───────────────
    public Page<Product> getProducts(
            int page, int size, String sort,
            Double minPrice, Double maxPrice,
            List<String> sizes, boolean sale, String q
    ) {
        if (page < 0) throw new BadRequestException("page must be 0 or greater");
        if (size < 1) throw new BadRequestException("size must be at least 1");
        size = Math.min(size, MAX_PAGE_SIZE);

        Sort sortObj = switch (sort == null ? "" : sort) {
            case "price_asc"  -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "popular"    -> Sort.by("totalSold").descending();
            case "rating"     -> Sort.by("averageRating").descending();
            default           -> Sort.by("createdAt").descending();
        };
        Pageable pageable = PageRequest.of(page, size, sortObj);

        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("isPublished").is(true));

        if (minPrice != null || maxPrice != null) {
            Criteria price = Criteria.where("price");
            if (minPrice != null) price = price.gte(minPrice);
            if (maxPrice != null) price = price.lte(maxPrice);
            filters.add(price);
        }
        if (sizes != null) {
            List<String> clean = sizes.stream()
                    .flatMap(s -> Arrays.stream(s.split(",")))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (!clean.isEmpty()) filters.add(Criteria.where("sizes").in(clean));
        }
        if (sale) filters.add(Criteria.where("discount").gt(0));
        if (q != null && !q.isBlank()) {
            Pattern p = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria().orOperator(
                    Criteria.where("name").regex(p),
                    Criteria.where("tags").regex(p),
                    Criteria.where("category").regex(p)
            ));
        }

        Query query = new Query(new Criteria().andOperator(filters.toArray(new Criteria[0])));
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), Product.class);
        List<Product> content = mongoTemplate.find(Query.of(query).with(pageable), Product.class);
        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }

    public Product getBySlug(String slug) {
        return productRepository.findBySlugAndIsPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + slug));
    }

    public Product getById(String id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
    }

    public boolean exists(String id) {
        return id != null && productRepository.existsById(id);
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

    // ── Admin: create / update / delete ──────────────────────
    public Product createProduct(ProductRequest req) {
        String slug = slugify(req.getName());
        if (slug.isEmpty()) slug = "product-" + System.currentTimeMillis();
        if (productRepository.existsBySlug(slug)) {
            slug = slug + "-" + System.currentTimeMillis();
        }

        List<String> sizes = req.getSizes() != null && !req.getSizes().isEmpty() ? req.getSizes() : DEFAULT_SIZES;
        validateSizeKeys(sizes);
        // every size must have a stock entry (0 if not given) so cart/order code never meets a null map
        Map<String, Integer> stock = new HashMap<>();
        for (String s : sizes) stock.put(s, 0);
        if (req.getStock() != null) {
            req.getStock().forEach((k, v) -> {
                if (v == null || v < 0) throw new BadRequestException("Stock for size " + k + " cannot be negative");
                validateSizeKeys(List.of(k));
                stock.put(k, v);
            });
        }

        Product product = Product.builder()
                .name(req.getName().trim())
                .slug(slug)
                .description(req.getDescription())
                .price(req.getPrice())
                .originalPrice(req.getOriginalPrice())
                .discount(req.getDiscount() != null ? req.getDiscount() : 0)
                .images(new ArrayList<>(req.getImages()))
                .sizes(new ArrayList<>(sizes))
                .stock(stock)
                .build();

        if (req.getDetails()  != null) product.setDetails(req.getDetails());
        if (req.getCare()     != null) product.setCare(req.getCare());
        if (req.getTags()     != null) product.setTags(req.getTags());
        if (req.getCategory() != null) product.setCategory(req.getCategory());
        if (req.getMaterial() != null) product.setMaterial(req.getMaterial());
        if (req.getWeight()   != null) product.setWeight(req.getWeight());
        if (req.getFeatured() != null) product.setFeatured(req.getFeatured());
        if (req.getNewProduct() != null) product.setNew(req.getNewProduct());
        if (req.getPublished()  != null) product.setPublished(req.getPublished());

        return productRepository.save(product);
    }

    /** Partial update: only the fields present in the request are changed. */
    public Product updateProduct(String id, ProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        if (req.getName()          != null) product.setName(req.getName().trim());
        if (req.getDescription()   != null) product.setDescription(req.getDescription());
        if (req.getPrice()         != null) {
            if (req.getPrice() <= 0) throw new BadRequestException("Price must be positive");
            product.setPrice(req.getPrice());
        }
        if (req.getOriginalPrice() != null) product.setOriginalPrice(req.getOriginalPrice());
        if (req.getDiscount()      != null) product.setDiscount(req.getDiscount());
        if (req.getImages()        != null) product.setImages(req.getImages());
        if (req.getSizes()         != null) {
            validateSizeKeys(req.getSizes());
            product.setSizes(req.getSizes());
        }
        if (req.getStock()         != null) {
            Map<String, Integer> stock = new HashMap<>(product.getStock() == null ? Map.of() : product.getStock());
            req.getStock().forEach((k, v) -> {
                if (v == null || v < 0) throw new BadRequestException("Stock for size " + k + " cannot be negative");
                validateSizeKeys(List.of(k));
                stock.put(k, v);
            });
            product.setStock(stock);
            product.setSoldOut(stock.values().stream().allMatch(v -> v <= 0));
        }
        if (req.getDetails()       != null) product.setDetails(req.getDetails());
        if (req.getCare()          != null) product.setCare(req.getCare());
        if (req.getTags()          != null) product.setTags(req.getTags());
        if (req.getCategory()      != null) product.setCategory(req.getCategory());
        if (req.getMaterial()      != null) product.setMaterial(req.getMaterial());
        if (req.getWeight()        != null) product.setWeight(req.getWeight());
        if (req.getFeatured()      != null) product.setFeatured(req.getFeatured());
        if (req.getNewProduct()    != null) product.setNew(req.getNewProduct());
        if (req.getPublished()     != null) product.setPublished(req.getPublished());

        return productRepository.save(product);
    }

    public void deleteProduct(String id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        productRepository.delete(product);
    }

    // ── Stock (atomic — safe under concurrent orders) ────────

    /**
     * Atomically takes {@code quantity} from a size's stock. The condition and the
     * decrement happen in ONE database operation, so two buyers can never both
     * take the last unit.
     *
     * @return true if the stock was reserved, false if there was not enough left
     */
    public boolean tryDecrementStock(String productId, String size, int quantity) {
        requireValidSize(size);
        Query q = new Query(Criteria.where("id").is(productId).and("stock." + size).gte(quantity));
        Update u = new Update().inc("stock." + size, -quantity).inc("totalSold", quantity);
        UpdateResult r = mongoTemplate.updateFirst(q, u, Product.class);
        if (r.getModifiedCount() == 0) return false;
        refreshSoldOutFlag(productId);
        return true;
    }

    /** Gives stock back (only call this for stock that was previously taken). */
    public void incrementStock(String productId, String size, int quantity) {
        requireValidSize(size);
        Query q = new Query(Criteria.where("id").is(productId));
        Update u = new Update().inc("stock." + size, quantity).inc("totalSold", -quantity).set("isSoldOut", false);
        mongoTemplate.updateFirst(q, u, Product.class);
    }

    private void refreshSoldOutFlag(String productId) {
        productRepository.findById(productId).ifPresent(p -> {
            boolean allOut = p.getStock() != null && p.getStock().values().stream().allMatch(v -> v <= 0);
            if (allOut != p.isSoldOut()) {
                mongoTemplate.updateFirst(new Query(Criteria.where("id").is(productId)),
                        new Update().set("isSoldOut", allOut), Product.class);
            }
        });
    }

    public void updateRating(String productId, double avgRating, int numReviews) {
        productRepository.findById(productId).ifPresent(p -> {
            p.setAverageRating(avgRating);
            p.setNumReviews(numReviews);
            productRepository.save(p);
        });
    }

    // ── helpers ──────────────────────────────────────────────
    private static String slugify(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
    }

    private static void validateSizeKeys(Collection<String> sizes) {
        for (String s : sizes) {
            if (s == null || !SIZE_KEY.matcher(s).matches()) {
                throw new BadRequestException("Invalid size: " + s);
            }
        }
    }

    private static void requireValidSize(String size) {
        if (size == null || !SIZE_KEY.matcher(size).matches()) {
            throw new BadRequestException("Invalid size: " + size);
        }
    }
}
