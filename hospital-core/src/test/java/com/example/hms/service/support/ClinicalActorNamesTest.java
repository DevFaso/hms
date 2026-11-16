package com.example.hms.service.support;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClinicalActorNamesTest {
    @Test
    void aListUsesOneScalarProjectionAndNeverLoadsUserEntitiesPerRow() {
        UserRepository users = mock(UserRepository.class);
        UUID id = UUID.randomUUID();
        var row = mock(UserRepository.ClinicalActorName.class);
        when(row.getId()).thenReturn(id);
        when(row.getFirstName()).thenReturn("Awa");
        when(row.getLastName()).thenReturn("Sanou");
        when(users.findClinicalActorNames(java.util.List.of(id))).thenReturn(java.util.List.of(row));
        var names = new ClinicalActorNames(users).batch(java.util.Arrays.asList(id, null, id));
        for (int index = 0; index < 100; index++) assertThat(names.apply(id, "historic.login")).isEqualTo("Awa Sanou");
        assertThat(names.apply(UUID.randomUUID(), "Saved human name")).isEqualTo("Saved human name");
        verify(users).findClinicalActorNames(java.util.List.of(id));
        verifyNoMoreInteractions(users);
    }

    @Test
    void renamingAnActorChangesTheDisplayWhileTheStoredSnapshotRemainsTheFallback() {
        UserRepository users = mock(UserRepository.class);
        UUID id = UUID.randomUUID();
        User actor = new User(); actor.setFirstName("Awa"); actor.setLastName("Sanou");
        when(users.findById(id)).thenReturn(Optional.of(actor));
        var names = new ClinicalActorNames(users);
        assertThat(names.display(id, "Saved name")).isEqualTo("Awa Sanou");
        actor.setLastName("Diallo");
        assertThat(names.display(id, "Saved name")).isEqualTo("Awa Diallo");
        when(users.findById(id)).thenReturn(Optional.empty());
        assertThat(names.display(id, "Saved name")).isEqualTo("Saved name");
    }
    @Test
    void stableActorIdResolvesLegacyNamesEvenWhenTheLoginChanged() {
        UserRepository users = mock(UserRepository.class);
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setUsername("new.login");
        user.setFirstName(" Awa ");
        user.setLastName(" Ouédraogo ");
        when(users.findById(id)).thenReturn(Optional.of(user));
        var names = new ClinicalActorNames(users);
        assertThat(names.display(id, "old.login")).isEqualTo("Awa Ouédraogo");
        assertThat(names.display(id, "Prior display name")).isEqualTo("Awa Ouédraogo");
        user.setDeleted(true);
        assertThat(names.display(id, "old.login")).isEqualTo("Awa Ouédraogo");
        user.setFirstName(null);
        assertThat(names.display(id, "old.login")).isEqualTo("Ouédraogo");
        user.setLastName(" ");
        assertThat(names.display(id, "Prior display name")).isEqualTo("Prior display name");
        assertThat(names.display(id, null)).isEqualTo("new.login");
        when(users.findById(id)).thenReturn(Optional.empty());
        assertThat(names.display(id, "Historic snapshot")).isEqualTo("Historic snapshot");
        assertThat(names.display((UUID) null, null)).isNull();
        assertThat(ClinicalActorNames.display((User) null, null)).isNull();
    }
}
