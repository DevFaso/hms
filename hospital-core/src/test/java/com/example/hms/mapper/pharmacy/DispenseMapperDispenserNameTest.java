package com.example.hms.mapper.pharmacy;

import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dispensing history's "Dispensed by" column read a field the wire never
 * carried; the mapper now sends the dispenser's name beside their id.
 */
class DispenseMapperDispenserNameTest {

    private final DispenseMapper mapper = new DispenseMapper();

    private static Dispense dispensedBy(User user) {
        Dispense dispense = new Dispense();
        dispense.setId(UUID.randomUUID());
        dispense.setDispensedByUser(user);
        return dispense;
    }

    @Test
    void theDispenserIsNamedBesideTheirId() {
        User pharmacist = new User();
        pharmacist.setId(UUID.randomUUID());
        pharmacist.setFirstName(" Awa ");
        pharmacist.setLastName("Traoré");

        DispenseResponseDTO dto = mapper.toResponseDTO(dispensedBy(pharmacist));

        assertThat(dto.getDispensedBy()).isEqualTo(pharmacist.getId());
        assertThat(dto.getDispensedByName()).isEqualTo("Awa Traoré");
    }

    @Test
    void aUserWithNoNameOnFileIsNotNamed() {
        User nameless = new User();
        nameless.setId(UUID.randomUUID());

        assertThat(mapper.toResponseDTO(dispensedBy(nameless)).getDispensedByName()).isNull();
    }

    @Test
    void aDetachedProxyAnswersNullRatherThanFailingTheResponse() {
        User detached = mock(User.class);
        when(detached.getId()).thenReturn(UUID.randomUUID());
        when(detached.getFirstName()).thenThrow(new LazyInitializationException("no session"));

        DispenseResponseDTO dto = mapper.toResponseDTO(dispensedBy(detached));

        assertThat(dto.getDispensedBy()).isNotNull();
        assertThat(dto.getDispensedByName()).isNull();
    }
}
