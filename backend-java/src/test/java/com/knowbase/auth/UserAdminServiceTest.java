package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserAdminServiceTest {

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    void duplicateUsernameIsTranslatedToBusinessConflict() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.findActiveRoleIds(any())).thenReturn(List.of(1));
        when(encoder.encode(anyString())).thenReturn("hash");
        when(repository.insertUser(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_username"));
        CurrentUser.set(new AuthUser(1, "admin", "管理员", List.of("SYS_ADMIN")));
        UserAdminService service = new UserAdminService(repository, encoder);

        assertThatThrownBy(() -> service.create(new UserAdminDtos.CreateRequest(
                "alice", "password-123", "Alice", "alice@example.com", List.of("USER"))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }
}
