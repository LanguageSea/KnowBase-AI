package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.kb.AuthzService;
import com.knowbase.kb.KbMemberDtos;
import com.knowbase.kb.KbMemberService;
import com.knowbase.kb.KbRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KbMemberOwnerGuardTest {

    private AuthzService authzService;
    private KbRepository repository;

    @BeforeEach
    void setUp() {
        CurrentUser.set(new AuthUser(1, "admin", "Admin", List.of("SYS_ADMIN")));
        authzService = mock(AuthzService.class);
        repository = mock(KbRepository.class);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void ownerMemberRoleCannotBeChangedThroughGrant() {
        when(repository.activeUserExists(1)).thenReturn(true);
        when(repository.findAccess(10, 1)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ACTIVE", "MANAGER")));
        KbMemberService service = new KbMemberService(authzService, repository);

        assertThatThrownBy(() -> service.grant(
                10, new KbMemberDtos.GrantRequest(1, "VIEWER")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(repository, never()).upsertMember(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }
}
