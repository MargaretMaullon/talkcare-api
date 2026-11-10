package com.talkcare.api.service;

import com.talkcare.api.domain.Role;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.error.UnauthenticatedException;
import com.talkcare.api.error.ValidationException;
import com.talkcare.api.repository.AccountRepository;
import com.talkcare.api.repository.ActivityRepository;
import com.talkcare.api.repository.PasswordResetRepository;
import com.talkcare.api.security.SessionTokenService;
import com.talkcare.api.web.dto.ForgotPasswordRequest;
import com.talkcare.api.web.dto.LoginRequest;
import com.talkcare.api.web.dto.RegisterProfessionalRequest;
import com.talkcare.api.web.dto.RegisterRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Sign-in, registration and password recovery. */
@Service
public class AuthService {

    private final AccountRepository accounts;
    private final PasswordResetRepository resets;
    private final PasswordEncoder passwords;
    private final SessionTokenService tokens;
    private final TransactionTemplate transaction;
    private final ActivityRepository activity;

    public AuthService(AccountRepository accounts, PasswordResetRepository resets, PasswordEncoder passwords,
                       SessionTokenService tokens, TransactionTemplate transaction, ActivityRepository activity) {
        this.activity = activity;
        this.accounts = accounts;
        this.resets = resets;
        this.passwords = passwords;
        this.tokens = tokens;
        this.transaction = transaction;
    }

    /** A signed-in account and its bearer token. */
    public record Session(Account account, String token) {
    }

    public Session login(LoginRequest request) {
        String loginId = request.loginId();
        String password = request.password();
        if (loginId == null || password == null || password.isEmpty()) {
            throw new ValidationException("Enter your username or email and password.");
        }
        for (AccountRepository.Credentials candidate : accounts.findCredentialsByLoginId(loginId)) {
            if (matches(password, candidate.passwordHash())) {
                Account account = accounts.findById(candidate.id()).orElseThrow();
                if (!account.isActive()) {
                    throw new UnauthenticatedException("This account is suspended or deactivated.");
                }
                if (request.role() != null && request.role() != account.role()) {
                    throw new UnauthenticatedException("This is not " + article(portalName(request.role()))
                            + " account. Use the " + portalName(account.role()) + " sign-in page.");
                }
                accounts.touchLastLogin(account.id());
                return startSession(account);
            }
        }
        throw new UnauthenticatedException("Invalid username, email, full name, or password.");
    }

    /** Creates a CLIENT account and signs it in. */
    public Session register(RegisterRequest request) {
        String passwordHash = passwords.encode(InputRules.newPassword(request.password()));
        Integer id = transaction.execute(status -> {
            int created = accounts.createClient(new AccountRepository.NewClient(
                    request.username(), request.email(), passwordHash,
                    request.fullName(), request.contactNo(), request.address()));
            activity.log(new ActivityRepository.NewActivity(created, "USER", created, "REGISTERED",
                    "New client account registered: " + nameOr(request.fullName(), request.username())));
            return created;
        });
        return startSession(accounts.findById(id).orElseThrow());
    }

    /**
     * Creates an ATTORNEY or PSYCHIATRIST account and signs it in. It stays PENDING_VERIFICATION,
     * without new clients, until an administrator approves its identity verification.
     */
    public Session registerProfessional(Role role, RegisterProfessionalRequest request) {
        String passwordHash = passwords.encode(InputRules.newPassword(request.password()));
        Integer id = transaction.execute(status -> {
            int created = accounts.createProfessional(new AccountRepository.NewProfessional(
                    role, request.username(), request.email(), passwordHash, request.fullName(),
                    request.contactNo(), request.address(), request.licenseNo(), request.affiliation()));
            activity.log(new ActivityRepository.NewActivity(created, "USER", created, "REGISTERED",
                    "New " + portalName(role).toLowerCase() + " account registered: "
                            + nameOr(request.fullName(), request.username())));
            return created;
        });
        return startSession(accounts.findById(id).orElseThrow());
    }

    private static String portalName(Role role) {
        String code = role.name();
        return code.charAt(0) + code.substring(1).toLowerCase();
    }

    private static String nameOr(String fullName, String username) {
        return fullName == null || fullName.isBlank() ? username : fullName;
    }

    private static String article(String word) {
        return ("AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "an " : "a ") + word;
    }

    /**
     * Files a password reset for an administrator to approve; the new password takes effect only
     * after approval. The response never reveals whether the email exists.
     */
    public String requestPasswordReset(ForgotPasswordRequest request) {
        if (request.email() == null) {
            throw new ValidationException("Enter the email address of your account.");
        }
        String passwordHash = passwords.encode(InputRules.newPassword(request.newPassword()));
        accounts.findByEmail(request.email()).ifPresent(user ->
                transaction.executeWithoutResult(status -> {
                    resets.replacePending(user.id(), passwordHash);
                    activity.log(new ActivityRepository.NewActivity(user.id(), "PASSWORD_RESET", user.id(), "REQUESTED",
                            "Password reset requested for " + user.email()));
                }));
        return "Request sent. An administrator will review it; once approved, sign in with your new password.";
    }

    private Session startSession(Account account) {
        return new Session(account, tokens.issue(account));
    }

    /** bcrypt rejects passwords over 72 bytes; such a password cannot match any stored hash. */
    private boolean matches(String password, String hash) {
        try {
            return passwords.matches(password, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

}
