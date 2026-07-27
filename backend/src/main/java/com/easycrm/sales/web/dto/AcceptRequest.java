package com.easycrm.sales.web.dto;

import java.time.LocalDate;

public record AcceptRequest(String poReference, LocalDate poDate) {}
