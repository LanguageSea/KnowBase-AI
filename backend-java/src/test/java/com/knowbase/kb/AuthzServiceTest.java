package com.knowbase.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthzServiceTest {

    @Mock
    private KbRepository kbRepository;

    private AuthzService authzService;

    @BeforeEach
    void setUp() {
        authzService = new AuthzService(kbRepository);
    }

    @Test
    void ownerHasReadWriteManage() {
        when(kbRepository.findAccess(10, 1)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ACTIVE", null)));

        assertThat(authzService.canReadKb(1, 10)).isTrue();
        assertThat(authzService.canWriteKb(1, 10)).isTrue();
        assertThat(authzService.canManageKb(1, 10)).isTrue();
    }

    @Test
    void viewerCanOnlyRead() {
        when(kbRepository.findAccess(10, 2)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ACTIVE", "VIEWER")));

        assertThat(authzService.canReadKb(2, 10)).isTrue();
        assertThat(authzService.canWriteKb(2, 10)).isFalse();
        assertThat(authzService.canManageKb(2, 10)).isFalse();
    }

    @Test
    void editorCanReadAndWriteButNotManage() {
        when(kbRepository.findAccess(10, 2)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ACTIVE", "EDITOR")));

        assertThat(authzService.canReadKb(2, 10)).isTrue();
        assertThat(authzService.canWriteKb(2, 10)).isTrue();
        assertThat(authzService.canManageKb(2, 10)).isFalse();
    }

    @Test
    void managerCanManage() {
        when(kbRepository.findAccess(10, 2)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ACTIVE", "MANAGER")));

        assertThat(authzService.canManageKb(2, 10)).isTrue();
    }

    @Test
    void removedMemberIsDenied() {
        when(kbRepository.findAccess(10, 2)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ACTIVE", null)));

        assertThat(authzService.canReadKb(2, 10)).isFalse();
    }

    @Test
    void archivedKbIsDeniedEvenForOwner() {
        when(kbRepository.findAccess(10, 1)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ARCHIVED", "MANAGER")));

        assertThat(authzService.canReadKb(1, 10)).isFalse();
        assertThat(authzService.canWriteKb(1, 10)).isFalse();
        assertThat(authzService.canManageKb(1, 10)).isFalse();
    }

    @Test
    void nonexistentKbIsDenied() {
        when(kbRepository.findAccess(99, 7)).thenReturn(Optional.empty());

        assertThat(authzService.canReadKb(7, 99)).isFalse();
    }
}
