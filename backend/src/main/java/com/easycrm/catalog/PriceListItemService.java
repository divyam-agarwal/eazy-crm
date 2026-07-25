package com.easycrm.catalog;

import com.easycrm.catalog.web.dto.PriceListItemRequest;
import com.easycrm.catalog.web.dto.PriceListItemResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PriceListItemService {

    private final PriceListItemRepository items;
    private final PriceListRepository priceLists;
    private final ProductRepository products;

    public PriceListItemService(PriceListItemRepository items, PriceListRepository priceLists,
                                ProductRepository products) {
        this.items = items;
        this.priceLists = priceLists;
        this.products = products;
    }

    @Transactional
    public PriceListItemResponse add(UUID priceListId, PriceListItemRequest req) {
        requirePriceList(priceListId);
        requireProduct(req.productId());
        validateXor(req);
        items.findByPriceListIdAndProductId(priceListId, req.productId()).ifPresent(i -> {
            throw new ConflictException("this product is already priced in this list");
        });
        PriceListItem saved = items.save(new PriceListItem(priceListId, req.productId(),
            req.overrideRate(), req.discountPct()));
        return PriceListItemResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<PriceListItemResponse> list(UUID priceListId) {
        requirePriceList(priceListId);
        return items.findByPriceListId(priceListId).stream().map(PriceListItemResponse::of).toList();
    }

    @Transactional
    public void delete(UUID priceListId, UUID itemId) {
        PriceListItem i = items.findById(itemId)
            .orElseThrow(() -> new NotFoundException("price list item not found"));
        if (!i.getPriceListId().equals(priceListId)) {
            throw new NotFoundException("price list item not found");
        }
        items.delete(i);
    }

    private void validateXor(PriceListItemRequest req) {
        boolean hasRate = req.overrideRate() != null;
        boolean hasDiscount = req.discountPct() != null;
        if (hasRate == hasDiscount) { // both set OR both null
            throw new ValidationException("overrideRate",
                "exactly one of overrideRate or discountPct must be set");
        }
    }

    private void requirePriceList(UUID priceListId) {
        priceLists.findById(priceListId)
            .orElseThrow(() -> new NotFoundException("price list not found"));
    }

    private void requireProduct(UUID productId) {
        products.findById(productId)
            .orElseThrow(() -> new NotFoundException("product not found"));
    }
}
