package com.example.hms.utility;

import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.model.platform.MllpAllowedSender;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link Hl7FieldBounds} constant is a copy of a column width, and this
 * is what keeps the copy honest.
 *
 * <p>Without it nothing links the two. Widen {@code lab_specimens.accession_number}
 * in a migration and OBR-2 values that now match real specimens are still
 * refused; narrow a column and a value passes the bound, then fails on flush -
 * the exact failure the bounds exist to remove. Read from the entity's
 * {@code @Column(length)}, which {@code EntitySchemaValidationIT} already
 * holds to the migrations.
 */
class Hl7FieldBoundsColumnWidthTest {

    private static int columnLength(Class<?> entity, String field) {
        try {
            Column column = entity.getDeclaredField(field).getAnnotation(Column.class);
            assertThat(column).as("%s.%s has @Column", entity.getSimpleName(), field).isNotNull();
            return column.length();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(entity.getSimpleName() + "." + field + " no longer exists", e);
        }
    }

    /** The column each bound mirrors: the bound must equal it exactly. */
    static Stream<Arguments> mirroredColumns() {
        return Stream.of(
            Arguments.of("MSH-3", Hl7FieldBounds.SENDER_FIELD_MAX, MllpAllowedSender.class, "sendingApplication"),
            Arguments.of("MSH-4", Hl7FieldBounds.SENDER_FIELD_MAX, MllpAllowedSender.class, "sendingFacility"),
            Arguments.of("MSH-9", Hl7FieldBounds.MESSAGE_TYPE_MAX, IntegrationMessageEvent.class, "messageType"),
            Arguments.of("MSH-10", Hl7FieldBounds.MESSAGE_CONTROL_ID_MAX, LabResult.class, "sourceMessageControlId"),
            Arguments.of("MSH-10", Hl7FieldBounds.MESSAGE_CONTROL_ID_MAX, Admission.class, "externalMessageControlId"),
            Arguments.of("MSH-10", Hl7FieldBounds.MESSAGE_CONTROL_ID_MAX, Encounter.class, "externalMessageControlId"),
            Arguments.of("OBR-2", Hl7FieldBounds.PLACER_ORDER_NUMBER_MAX, LabSpecimen.class, "accessionNumber"),
            Arguments.of("PID-3/MRG-1", Hl7FieldBounds.MRN_MAX, EmpiIdentityAlias.class, "aliasValue"),
            Arguments.of("PV1-19", Hl7FieldBounds.VISIT_NUMBER_MAX, Admission.class, "externalVisitNumber"),
            Arguments.of("PV1-19", Hl7FieldBounds.VISIT_NUMBER_MAX, Encounter.class, "externalVisitNumber"),
            // PV1-3's point of care matches a department code OR name; the
            // wider of the two is the one a value could still match.
            Arguments.of("PV1-3", Hl7FieldBounds.ASSIGNED_LOCATION_MAX, Department.class, "name"));
    }

    @ParameterizedTest(name = "{0} bound equals {2}.{3}")
    @MethodSource("mirroredColumns")
    void eachBoundEqualsTheColumnItMirrors(String field, int bound, Class<?> entity, String column) {
        assertThat(bound)
            .as("%s bound vs %s.%s - change both together", field, entity.getSimpleName(), column)
            .isEqualTo(columnLength(entity, column));
    }

    /** Other columns the same field is written to: the bound must never exceed them. */
    static Stream<Arguments> otherWrittenColumns() {
        return Stream.of(
            Arguments.of("MSH-3", Hl7FieldBounds.SENDER_FIELD_MAX, LabResult.class, "sourceSendingApplication"),
            Arguments.of("MSH-4", Hl7FieldBounds.SENDER_FIELD_MAX, LabResult.class, "sourceSendingFacility"),
            Arguments.of("MSH-3", Hl7FieldBounds.SENDER_FIELD_MAX, Admission.class, "externalSendingApplication"),
            Arguments.of("MSH-4", Hl7FieldBounds.SENDER_FIELD_MAX, Admission.class, "externalSendingFacility"),
            Arguments.of("MSH-3", Hl7FieldBounds.SENDER_FIELD_MAX, Encounter.class, "externalSendingApplication"),
            Arguments.of("MSH-4", Hl7FieldBounds.SENDER_FIELD_MAX, Encounter.class, "externalSendingFacility"));
    }

    @ParameterizedTest(name = "{0} bound fits {2}.{3}")
    @MethodSource("otherWrittenColumns")
    void eachBoundFitsEveryOtherColumnTheFieldIsWrittenTo(String field, int bound, Class<?> entity, String column) {
        assertThat(bound)
            .as("%s is written to %s.%s", field, entity.getSimpleName(), column)
            .isLessThanOrEqualTo(columnLength(entity, column));
    }

    @Test
    @DisplayName("A supplementary-plane character counts once, as VARCHAR(n) counts it")
    void widthIsCountedInCharactersNotUtf16Units() {
        // U+1F3E5 (hospital emoji) is two UTF-16 units and one character.
        String hospital = new String(Character.toChars(0x1F3E5));
        String fifty = hospital.repeat(50);

        assertThat(fifty).hasSize(100);
        assertThat(Hl7FieldBounds.fits(fifty, 50)).isTrue();
        assertThat(Hl7FieldBounds.fits(fifty + hospital, 50)).isFalse();
    }
}
