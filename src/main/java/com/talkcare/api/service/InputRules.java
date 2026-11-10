package com.talkcare.api.service;

import com.talkcare.api.domain.UserStatus;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.domain.account.Client;
import com.talkcare.api.error.ForbiddenException;
import com.talkcare.api.error.ValidationException;
import java.nio.charset.StandardCharsets;

/** Validation rules that depend on more than one field's shape (Bean Validation covers the rest). */
public final class InputRules {

    private static final int MIN_PASSWORD = 8;
    /** bcrypt only uses the first 72 bytes of a password. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private InputRules() {
    }

    /** Clients may use chat, booking and reports only once an administrator has verified them. */
    public static void requireVerifiedClient(Account actor) {
        if (actor instanceof Client && actor.status() != UserStatus.VERIFIED) {
            throw new ForbiddenException("Your account must be verified by an administrator before you can use TalkCare services.");
        }
    }

    /** A new password: 8+ characters, at most 72 bytes. Never trimmed. */
    public static String newPassword(String value) {
        String password = value == null ? "" : value;
        if (password.length() < MIN_PASSWORD) {
            throw new ValidationException("Password must be at least 8 characters long.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ValidationException("Password must be at most 72 characters long.");
        }
        return password;
    }

    /** Like {@link #newPassword}, but an empty value means "unchanged" and returns null. */
    public static String optionalNewPassword(String value) {
        return value == null || value.isEmpty() ? null : newPassword(value);
    }

    /** A positive integer id from a path segment or JSON value. */
    public static int positiveId(String value, String name) {
        try {
            int id = Integer.parseInt(value == null ? "" : value.strip());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new ValidationException("Invalid " + name + ".");
    }
}
