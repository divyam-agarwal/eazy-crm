package com.easycrm.sales;

import com.easycrm.catalog.PriceListItem;
import com.easycrm.catalog.PriceListItemRepository;
import com.easycrm.catalog.Product;
import com.easycrm.catalog.ProductRepository;
import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.platform.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Resolves the default line rate for (customer, product): the customer's price-list override
 * or discount applied to the product base rate, else the base rate. Returns the product
 * snapshot fields the quotation item copies. The rate is a DEFAULT — the caller may override it.
 */
@Service
public class PriceResolver {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final PriceListItemRepository priceListItems;

    public PriceResolver(CustomerRepository customers, ProductRepository products,
                         PriceListItemRepository priceListItems) {
        this.customers = customers;
        this.products = products;
        this.priceListItems = priceListItems;
    }

    public record Resolved(BigDecimal rate, String name, String hsn, String uom, BigDecimal gstRate) {}

    @Transactional(readOnly = true)
    public Resolved resolve(UUID customerId, UUID productId) {
        Customer customer = customers.findById(customerId)
            .orElseThrow(() -> new NotFoundException("customer not found"));
        Product product = products.findById(productId)
            .orElseThrow(() -> new NotFoundException("product not found"));

        BigDecimal rate = product.getBaseRate();
        UUID priceListId = customer.getPriceListId();
        if (priceListId != null) {
            PriceListItem item = priceListItems
                .findByPriceListIdAndProductId(priceListId, productId).orElse(null);
            if (item != null) {
                if (item.getOverrideRate() != null) {
                    rate = item.getOverrideRate();
                } else if (item.getDiscountPct() != null) {
                    BigDecimal factor = BigDecimal.ONE.subtract(item.getDiscountPct().divide(HUNDRED));
                    rate = product.getBaseRate().multiply(factor).setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return new Resolved(rate, product.getName(), product.getHsnCode(),
                            product.getUom().name(), product.getGstRate());
    }
}
