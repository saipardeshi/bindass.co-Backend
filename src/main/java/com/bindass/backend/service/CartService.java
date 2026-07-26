package com.bindass.backend.service;

import com.bindass.backend.dto.request.CartRequest;
import com.bindass.backend.dto.response.CartResponse;
import com.bindass.backend.exception.BadRequestException;
import com.bindass.backend.exception.ResourceNotFoundException;
import com.bindass.backend.model.Cart;
import com.bindass.backend.model.Product;
import com.bindass.backend.repository.CartRepository;
import com.bindass.backend.repository.ProductRepository;
import com.bindass.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    // ── Public API ────────────────────────────────────────────

    public CartResponse getCart(String userEmail) {
        Cart cart = getOrCreateCart(resolveUserId(userEmail));
        return enrich(cart);
    }

    public CartResponse addItem(String userEmail, CartRequest.AddItemRequest req) {
        String userId = resolveUserId(userEmail);
        Cart cart = getOrCreateCart(userId);

        // Validate the product/size actually exist before adding — fail
        // fast rather than silently storing a dangling reference.
        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (!product.getSizes().contains(req.getSize())) {
            throw new BadRequestException("Size " + req.getSize() + " is not available for this product");
        }

        List<Cart.CartItem> items = cart.getItems();
        Cart.CartItem existing = items.stream()
                .filter(i -> i.getProductId().equals(req.getProductId())
                        && i.getSize().equals(req.getSize()))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + req.getQuantity());
        } else {
            items.add(Cart.CartItem.builder()
                    .productId(req.getProductId())
                    .size(req.getSize())
                    .quantity(req.getQuantity())
                    .build());
        }

        cartRepository.save(cart);
        return enrich(cart);
    }

    // Sets an exact quantity; quantity=0 removes the line item.
    public CartResponse updateItem(String userEmail, CartRequest.UpdateItemRequest req) {
        Cart cart = getOrCreateCart(resolveUserId(userEmail));

        if (req.getQuantity() == 0) {
            cart.getItems().removeIf(i ->
                    i.getProductId().equals(req.getProductId()) && i.getSize().equals(req.getSize()));
        } else {
            cart.getItems().stream()
                    .filter(i -> i.getProductId().equals(req.getProductId())
                            && i.getSize().equals(req.getSize()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Item not in cart"))
                    .setQuantity(req.getQuantity());
        }

        cartRepository.save(cart);
        return enrich(cart);
    }

    public CartResponse removeItem(String userEmail, String productId, String size) {
        Cart cart = getOrCreateCart(resolveUserId(userEmail));
        cart.getItems().removeIf(i ->
                i.getProductId().equals(productId) && i.getSize().equals(size));
        cartRepository.save(cart);
        return enrich(cart);
    }

    public void clearCart(String userEmail) {
        Cart cart = getOrCreateCart(resolveUserId(userEmail));
        cart.getItems().clear();
        cartRepository.save(cart);
    }

    // Called right after login — merges a guest's localStorage cart into
    // the server cart instead of overwriting it, so items don't get lost
    // if the user already had a server-side cart from another device.
    public CartResponse mergeGuestCart(String userEmail, CartRequest.MergeRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return getCart(userEmail);
        }
        for (CartRequest.AddItemRequest item : req.getItems()) {
            try {
                addItem(userEmail, item);
            } catch (ResourceNotFoundException | BadRequestException e) {
                // A guest-cart item may reference a product that's since been
                // removed/changed size options — skip it rather than fail
                // the whole merge.
                log.warn("Skipped guest cart item during merge: {}", e.getMessage());
            }
        }
        return getCart(userEmail);
    }

    // ── Internal helpers ──────────────────────────────────────

    private String resolveUserId(String userEmail) {
        return userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                .getId();
    }

    private Cart getOrCreateCart(String userId) {
        return cartRepository.findByUserId(userId)
                .orElseGet(() -> cartRepository.save(
                        Cart.builder().userId(userId).build()
                ));
    }

    // Re-reads current product data for every line item so the client never
    // sees a stale price/name/image, and flags items whose quantity now
    // exceeds live stock (frontend can warn before checkout instead of the
    // order failing at OrderService.createOrder).
    private CartResponse enrich(Cart cart) {
        if (cart.getItems().isEmpty()) {
            return CartResponse.builder().items(List.of()).subtotal(0).totalItems(0).build();
        }

        List<String> productIds = cart.getItems().stream()
                .map(Cart.CartItem::getProductId)
                .distinct()
                .toList();
        Map<String, Product> productsById = new HashMap<>();
        productRepository.findAllById(productIds)
                .forEach(p -> productsById.put(p.getId(), p));

        double subtotal = 0;
        int totalItems = 0;
        List<CartResponse.Item> responseItems = new ArrayList<>();

        for (Cart.CartItem item : cart.getItems()) {
            Product product = productsById.get(item.getProductId());
            if (product == null) {
                // Product was deleted since being added — surface it as a
                // stock issue rather than throwing, so the rest of the cart
                // still renders.
                responseItems.add(CartResponse.Item.builder()
                        .productId(item.getProductId())
                        .name("(no longer available)")
                        .size(item.getSize())
                        .quantity(item.getQuantity())
                        .availableStock(0)
                        .stockIssue(true)
                        .build());
                continue;
            }

            int available = product.getStock().getOrDefault(item.getSize(), 0);
            boolean stockIssue = available < item.getQuantity();

            responseItems.add(CartResponse.Item.builder()
                    .productId(product.getId())
                    .name(product.getName())
                    .slug(product.getSlug())
                    .image(product.getImages().isEmpty() ? "" : product.getImages().get(0))
                    .price(product.getPrice())
                    .size(item.getSize())
                    .quantity(item.getQuantity())
                    .availableStock(available)
                    .stockIssue(stockIssue)
                    .build());

            subtotal += product.getPrice() * item.getQuantity();
            totalItems += item.getQuantity();
        }

        return CartResponse.builder()
                .items(responseItems)
                .subtotal(subtotal)
                .totalItems(totalItems)
                .build();
    }
}