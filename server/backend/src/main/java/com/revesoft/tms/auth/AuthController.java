package com.revesoft.tms.auth;

import com.revesoft.tms.auth.AuthService.ChangePasswordRequest;
import com.revesoft.tms.auth.AuthService.ForgotPasswordRequest;
import com.revesoft.tms.auth.AuthService.LoginRequest;
import com.revesoft.tms.auth.AuthService.LogoutRequest;
import com.revesoft.tms.auth.AuthService.Me;
import com.revesoft.tms.auth.AuthService.RefreshRequest;
import com.revesoft.tms.auth.AuthService.SignupRequest;
import com.revesoft.tms.auth.AuthService.SignupResponse;
import com.revesoft.tms.auth.AuthService.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return service.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return service.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        service.logout(request);
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return service.signup(request);
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        service.forgotPassword(request);
    }

    @GetMapping("/me")
    public Me me() {
        return service.currentUser();
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(request);
    }
}
