package com.talkcare.api.domain.account;

import java.time.Instant;

/** A forgot-password request waiting for an administrator. */
public record PendingPasswordReset(int id, int userId, String role, String fullName, String username,
                                   String email, Instant submittedAt) {
}
