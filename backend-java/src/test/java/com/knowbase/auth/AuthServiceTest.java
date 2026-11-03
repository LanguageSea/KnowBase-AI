package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.knowbase.auth.UserRepository.UserState;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private UserRepository repository;
    private JwtService jwtService;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptService loginAttempts;
    private AuthService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginAttempts = mock(LoginAttemptService.class);
        service = new AuthService(repository, jwtService, passwordEncoder, loginAttempts);
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    void disabledUserTokenIsRejectedImmediately() {
        when(repository.findStateById(2)).thenReturn(Optional.of(
                new UserState(2, "user", "普通用户", "DISABLED", 0)));

        assertThatThrownBy(() -> service.authenticate(new JwtService.ParsedToken(2, "user", 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void staleTokenVersionIsRejectedImmediately() {
        when(repository.findStateById(2)).thenReturn(Optional.of(
                new UserState(2, "user", "普通用户", "ACTIVE", 7)));

        assertThatThrownBy(() -> service.authenticate(new JwtService.ParsedToken(2, "user", 6)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void successfulLoginRotatesTokenVersionToInvalidateOtherDevices() {
        when(repository.findByUsername("user")).thenReturn(Optional.of(
                new UserRepository.UserAccount(
                        2, "user", "bcrypt-hash", "普通用户", "ACTIVE", 7)));
        when(passwordEncoder.matches("secret", "bcrypt-hash")).thenReturn(true);
        when(repository.rotateTokenVersionForLogin(
                org.mockito.ArgumentMatchers.eq(2), any())).thenReturn(8);
        when(repository.findRoleCodes(2)).thenReturn(List.of("USER"));
        when(jwtService.issue(2, "user", 8)).thenReturn("new-token");
        when(jwtService.ttlSeconds()).thenReturn(3600L);

        LoginResponse response = service.login(
                new LoginRequest("user", "secret"), "127.0.0.1", "test-agent");

        assertThat(response.token()).isEqualTo("new-token");
        verify(repository).rotateTokenVersionForLogin(
                org.mockito.ArgumentMatchers.eq(2), any());
    }

    @Test
    void logoutIncrementsTokenVersion() {
        CurrentUser.set(new AuthUser(2, "user", "普通用户", List.of("USER")));

        service.logout();

        verify(repository).invalidateTokens(org.mockito.ArgumentMatchers.eq(2), any());
    }

    @Test
    void changePasswordInvalidatesEverySession() {
        CurrentUser.set(new AuthUser(2, "user", "普通用户", List.of("USER")));
        when(repository.findPasswordHash(2)).thenReturn(Optional.of("old-hash"));
        when(passwordEncoder.matches("old-pass", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-pass-123")).thenReturn("new-hash");

        service.changePassword(new ChangePasswordRequest("old-pass", "new-pass-123"));

        verify(repository).updatePasswordAndInvalidateTokens(
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq("new-hash"),
                any());
    }
}
