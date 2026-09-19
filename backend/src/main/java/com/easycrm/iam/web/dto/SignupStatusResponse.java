package com.easycrm.iam.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"open"})
public record SignupStatusResponse(boolean open) {}
