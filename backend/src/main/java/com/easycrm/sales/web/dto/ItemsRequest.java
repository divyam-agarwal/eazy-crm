package com.easycrm.sales.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ItemsRequest(@NotEmpty @Valid List<ItemRequest> items) {}
