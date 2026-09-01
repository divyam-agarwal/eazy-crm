package com.easycrm.sales;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when the scheduled sweep expires a quotation. Deliberately carries no
 * actorUserId, unlike QuotationAcceptedEvent: no human did this, and an always-null field
 * would invite a caller to start populating it. The audit listener writes null itself.
 */
public record QuotationExpiredEvent(UUID quotationId, String quoteNo,
                                    UUID quotationVersionId, LocalDate validUntil) {}
