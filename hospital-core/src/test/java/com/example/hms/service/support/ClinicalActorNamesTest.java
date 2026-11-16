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
