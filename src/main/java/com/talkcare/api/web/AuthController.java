package com.talkcare.api.web;

import com.talkcare.api.domain.Role;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.service.AuthService;
import com.talkcare.api.web.dto.AccountResponse;
import com.talkcare.api.web.dto.ForgotPasswordRequest;
import com.talkcare.api.web.dto.LoginRequest;
import com.talkcare.api.web.dto.RegisterProfessionalRequest;
import com.talkcare.api.web.dto.RegisterRequest;
import com.talkcare.api.web.dto.SimpleResponses.Info;
import com.talkcare.api.web.dto.SimpleResponses.Success;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public AccountResponse login(@Valid @RequestBody LoginRequest request) {
        return toResponse(auth.login(request));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegisterRequest request) {
        return toResponse(auth.register(request));
    }

    @PostMapping("/register/attorney")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse registerAttorney(@Valid @RequestBody RegisterProfessionalRequest request) {
        return toResponse(auth.registerProfessional(Role.ATTORNEY, request));
    }

    @PostMapping("/register/psychiatrist")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse registerPsychiatrist(@Valid @RequestBody RegisterProfessionalRequest request) {
        return toResponse(auth.registerProfessional(Role.PSYCHIATRIST, request));
    }

    @PostMapping("/forgot-password")
    public Info forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return new Info(auth.requestPasswordReset(request));
    }

    /** Tokens are stateless JWTs; logout is acknowledged and the client discards its token. */
    @PostMapping("/logout")
    public Success logout() {
        return Success.OK;
    }

    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal Account actor) {
        return AccountResponse.from(actor);
    }

    private static AccountResponse toResponse(AuthService.Session session) {
        return AccountResponse.withSession(session.account(), session.token());
    }
}
