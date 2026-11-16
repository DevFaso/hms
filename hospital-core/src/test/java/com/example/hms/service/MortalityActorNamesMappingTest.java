package com.example.hms.service;

import com.example.hms.mapper.MortalityMapper;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.support.ClinicalActorNames;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MortalityActorNamesMappingTest {
    @Test
    void enteredInErrorResolvesLegacyLoginByIdAndFallsBackToTheSnapshotWhenTheAccountIsGone() {
        var users = mock(UserRepository.class);
        var mapper = new MortalityMapper(new ClinicalActorNames(users));
        var id = UUID.randomUUID();
        var user = new User();
        user.setFirstName("Awa");
        user.setLastName("Ouédraogo");
        var record = DeathRecord.builder().enteredInErrorAt(java.time.LocalDateTime.of(2026, 11, 15, 12, 0)).enteredInErrorById(id)
            .enteredInErrorByName("historic.login").enteredInErrorReason("User-authored reason").build();
        when(users.findById(id)).thenReturn(Optional.of(user));
        assertThat(mapper.toDto(record).getEnteredInErrorByName()).isEqualTo("Awa Ouédraogo");
        assertThat(mapper.toDto(record).getEnteredInErrorReason()).isEqualTo("User-authored reason");
        assertThat(record.getEnteredInErrorByName()).isEqualTo("historic.login");
        when(users.findById(id)).thenReturn(Optional.empty());
        assertThat(mapper.toDto(record).getEnteredInErrorByName()).isEqualTo("historic.login");
    }
}
