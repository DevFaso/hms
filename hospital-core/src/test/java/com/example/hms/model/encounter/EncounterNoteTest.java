package com.example.hms.model.encounter;

import com.example.hms.enums.EncounterNoteLinkType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EncounterNoteTest {

    @Test
    void clearLinksResetsInternalCollection() {
        EncounterNote note = new EncounterNote();
        note.addLink(EncounterNoteLink.builder()
            .artifactType(EncounterNoteLinkType.LAB_ORDER)
            .artifactId(UUID.randomUUID())
            .linkedAt(LocalDateTime.now())
            .build());

        assertThat(note.getLinks()).hasSize(1);

        assertThatCode(note::clearLinks).doesNotThrowAnyException();
        assertThat(note.getLinks()).isEmpty();

        note.addLink(EncounterNoteLink.builder()
            .artifactType(EncounterNoteLinkType.PRESCRIPTION)
            .artifactId(UUID.randomUUID())
            .linkedAt(LocalDateTime.now())
            .build());

        assertThat(note.getLinks()).hasSize(1);
    }
}
