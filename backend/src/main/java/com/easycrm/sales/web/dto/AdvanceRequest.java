package com.easycrm.sales.web.dto;

import com.easycrm.sales.EnquiryStage;
import jakarta.validation.constraints.NotNull;

public record AdvanceRequest(@NotNull EnquiryStage stage) {}
