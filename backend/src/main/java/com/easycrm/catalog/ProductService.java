package com.easycrm.catalog;

import com.easycrm.catalog.web.dto.ProductCreateRequest;
import com.easycrm.catalog.web.dto.ProductResponse;
import com.easycrm.catalog.web.dto.ProductUpdateRequest;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    // Compared with compareTo (not equals): BigDecimal("18") != BigDecimal("18.0") under equals.
    private static final BigDecimal[] ALLOWED_GST_RATES = {
        new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("3"),
        new BigDecimal("5"), new BigDecimal("12"), new BigDecimal("18"), new BigDecimal("28")
    };

    private final ProductRepository products;

    public ProductService(ProductRepository products) { this.products = products; }

    @Transactional
    public ProductResponse create(ProductCreateRequest req) {
        validate(req.hsnCode(), req.gstRate(), req.baseRate());
        products.findBySku(req.sku()).ifPresent(p -> {
            throw new ConflictException("product with this SKU already exists");
        });
        Product saved = products.save(new Product(req.sku(), req.name(), req.hsnCode(),
                                                  req.uom(), req.gstRate(), req.baseRate()));
        return ProductResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return ProductResponse.of(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(Boolean active, Pageable pageable) {
        var page = (active == null)
            ? products.findAll(pageable)
            : products.findByActive(active, pageable);
        return PageResponse.of(page.map(ProductResponse::of));
    }

    @Transactional
    public ProductResponse update(UUID id, ProductUpdateRequest req) {
        validate(req.hsnCode(), req.gstRate(), req.baseRate());
        Product p = find(id);
        p.update(req.name(), req.hsnCode(), req.uom(), req.gstRate(), req.baseRate());
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse deactivate(UUID id) {
        Product p = find(id);
        p.deactivate();
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse activate(UUID id) {
        Product p = find(id);
        p.activate();
        return ProductResponse.of(p);
    }

    private Product find(UUID id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("product not found"));
    }

    private void validate(String hsnCode, BigDecimal gstRate, BigDecimal baseRate) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (hsnCode != null && !hsnCode.isBlank()
                && !hsnCode.matches("\\d{4}|\\d{6}|\\d{8}")) {
            errors.put("hsnCode", "HSN code must be 4, 6, or 8 digits");
        }
        if (gstRate != null && !isAllowedRate(gstRate)) {
            errors.put("gstRate", "GST rate must be one of 0, 0.25, 3, 5, 12, 18, 28");
        }
        if (baseRate != null && baseRate.compareTo(BigDecimal.ZERO) < 0) {
            errors.put("baseRate", "base rate must not be negative");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private boolean isAllowedRate(BigDecimal rate) {
        for (BigDecimal allowed : ALLOWED_GST_RATES) {
            if (allowed.compareTo(rate) == 0) return true;
        }
        return false;
    }
}
