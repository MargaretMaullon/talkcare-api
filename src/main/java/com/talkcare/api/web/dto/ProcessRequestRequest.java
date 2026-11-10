package com.talkcare.api.web.dto;

import jakarta.validation.constraints.Size;

/**
 * An admin decision on a pending request: {@code type} VERIFICATION (APPROVE / REJECT) or
 * REPORT (IN_PROGRESS / RESOLVED / ADD_REMARKS) or PASSWORD_RESET (APPROVE / REJECT). {@code id} accepts a number or numeric string.
 */
public record ProcessRequestRequest(
        String type,
        String id,
        String action,
        @Size(max = 2000, message = Inputs.TOO_LONG) String reason,
        @Size(max = 2000, message = Inputs.TOO_LONG) String remarks) {

    public ProcessRequestRequest {
        reason = Inputs.trimToNull(reason);
        remarks = Inputs.trimToNull(remarks);
    }
}
