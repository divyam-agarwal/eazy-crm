package com.easycrm.catalog;

import com.easycrm.catalog.web.dto.PriceListRequest;
import com.easycrm.catalog.web.dto.PriceListResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.platform.web.SortAllowlist;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceListService {

    /** Sort fields a client may name. Anything else is a 422, not a 500 from JPA (F1-3). */
    private static final Set<String> SORTABLE = Set.of("name", "createdAt", "updatedAt");

    private final PriceListRepository priceLists;

    public PriceListService(PriceListRepository priceLists) {
        this.priceLists = priceLists;
    }

    @Transactional
    public PriceListResponse create(PriceListRequest req) {
        priceLists.findByName(req.name()).ifPresent(p -> {
            throw new ConflictException(
                    "a price list with this name already exists",
                    Map.of("name", "a price list with this name already exists"),
                    Map.of("name", "NAME_DUPLICATE"));
        });
        return PriceListResponse.of(priceLists.save(new PriceList(req.name())));
    }

    @Transactional(readOnly = true)
    public PriceListResponse get(UUID id) {
        return PriceListResponse.of(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PriceListResponse> list(Boolean active, String q, Pageable pageable) {
        SortAllowlist.require(pageable, SORTABLE);
        return PageResponse.of(priceLists
                .findAll(PriceListSpecifications.filter(active, q), pageable)
                .map(PriceListResponse::of));
    }

    @Transactional
    public PriceListResponse rename(UUID id, PriceListRequest req) {
        priceLists.findByName(req.name()).ifPresent(p -> {
            if (!p.getId().equals(id)) {
                throw new ConflictException(
                        "a price list with this name already exists",
                        Map.of("name", "a price list with this name already exists"),
                        Map.of("name", "NAME_DUPLICATE"));
            }
        });
        PriceList p = find(id);
        p.rename(req.name());
        return PriceListResponse.of(p);
    }

    @Transactional
    public PriceListResponse deactivate(UUID id) {
        PriceList p = find(id);
        p.deactivate();
        return PriceListResponse.of(p);
    }

    @Transactional
    public PriceListResponse activate(UUID id) {
        PriceList p = find(id);
        p.activate();
        return PriceListResponse.of(p);
    }

    private PriceList find(UUID id) {
        return priceLists.findById(id).orElseThrow(() -> new NotFoundException("price list not found"));
    }
}
