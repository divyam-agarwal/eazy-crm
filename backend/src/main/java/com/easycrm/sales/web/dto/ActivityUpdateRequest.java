package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * subjectType and subjectId are carried in the BODY, not merely in the path, because
 * ActivityRepository declares no by-id-alone lookup — that absence is what makes the
 * subject gate structural rather than conventional (spec §4.2, §9). The client is always
 * editing a row it just rendered inside a subject's timeline, so it has both to hand.
 *
 * <p>Only body and outcome are editable. type, occurredAt, subject and source are fixed
 * at creation: correcting a typo is a correction; changing which enquiry a call was about
 * is rewriting history.
 */
public record ActivityUpdateRequest(
    @NotNull SubjectType subjectType,
    @NotNull UUID subjectId,
    @Size(max = 2000) String body,
    @Size(max = 200) String outcome) {}
