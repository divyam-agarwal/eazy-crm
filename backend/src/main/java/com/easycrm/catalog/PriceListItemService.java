package com.easycrm.catalog;

import com.easycrm.catalog.web.dto.PriceListItemRequest;
import com.easycrm.catalog.web.dto.PriceListItemResponse;
import com.easycrm.catalog.web.dto.PriceListItemUpdateRequest;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceListItemService {

    private final PriceListItemRepository items;
    private final PriceListRepository priceLists;
    private final ProductRepository products;

    public PriceListItemService(
            PriceListItemRepository items, PriceListRepository priceLists, ProductRepository products) {
        this.items = items;
        this.priceLists = priceLists;
        this.products = products;
    }

    @Transactional
    public PriceListItemResponse add(UUID priceListId, PriceListItemRequest req) {
        requirePriceList(priceListId);
        requireProduct(req.productId());
        validateXor(req.overrideRate(), req.discountPct());
        validateRange(req.overrideRate(), req.discountPct());
        items.findByPriceListIdAndProductId(priceListId, req.productId()).ifPresent(i -> {
            throw new ConflictException(
                    "this product is already priced in this list",
                    Map.of("productId", "this product is already priced in this list"),
                    Map.of("productId", "PRODUCT_DUPLICATE"));
        });
        PriceListItem saved =
                items.save(new PriceListItem(priceListId, req.productId(), req.overrideRate(), req.discountPct()));
        return PriceListItemResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<PriceListItemResponse> list(UUID priceListId) {
        requirePriceList(priceListId);
        return items.findByPriceListId(priceListId).stream()
                .map(PriceListItemResponse::of)
                .toList();
    }

    @Transactional
    public void delete(UUID priceListId, UUID itemId) {
        PriceListItem i = items.findById(itemId).orElseThrow(() -> new NotFoundException("price list item not found"));
        if (!i.getPriceListId().equals(priceListId)) {
            throw new NotFoundException("price list item not found");
        }
        items.delete(i);
    }

    @Transactional
    public PriceListItemResponse update(UUID priceListId, UUID itemId, PriceListItemUpdateRequest req) {
        validateXor(req.overrideRate(), req.discountPct());
        validateRange(req.overrideRate(), req.discountPct());
        PriceListItem i = items.findById(itemId).orElseThrow(() -> new NotFoundException("price list item not found"));
        // Same gate as delete(): an item addressed through the wrong parent is "not found", never
        // silently updated. Cross-tenant rows are already invisible to RLS.
        if (!i.getPriceListId().equals(priceListId)) {
            throw new NotFoundException("price list item not found");
        }
        i.updateRates(req.overrideRate(), req.discountPct());
        return PriceListItemResponse.of(i);
    }

    private void validateXor(BigDecimal overrideRate, BigDecimal discountPct) {
        boolean hasRate = overrideRate != null;
        boolean hasDiscount = discountPct != null;
        if (hasRate == hasDiscount) { // both set OR both null
            throw new ValidationException(
                    "overrideRate", "exactly one of overrideRate or discountPct must be set", "RATE_RULE_XOR");
        }
    }

    private void validateRange(BigDecimal overrideRate, BigDecimal discountPct) {
        if (overrideRate != null && overrideRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException(
                    "overrideRate", "override rate must not be negative", "OVERRIDE_RATE_NEGATIVE");
        }
        if (discountPct != null
                && (discountPct.compareTo(BigDecimal.ZERO) < 0 || discountPct.compareTo(new BigDecimal("100")) > 0)) {
            throw new ValidationException(
                    "discountPct", "discount percent must be between 0 and 100", "DISCOUNT_PCT_RANGE");
        }
    }

    private void requirePriceList(UUID priceListId) {
        priceLists.findById(priceListId).orElseThrow(() -> new NotFoundException("price list not found"));
    }

    private void requireProduct(UUID productId) {
        products.findById(productId).orElseThrow(() -> new NotFoundException("product not found"));
    }
}
