package com.example.hms.mapper;

import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 18 guard. Class: "a reference-range copy that maps only some of the
 * range's fields". A stored range is copied into three payloads (the
 * definition DTO, the result DTO, and back from the editor's DTO). The
 * sex/age band, its age unit, the critical limits and the source must
 * survive every copy, or the screen shows one range while the grading used
 * another. Every field of {@link LabTestReferenceRange} is set by reflection
 * and must come back equal through each copy, so a field added later fails
 * here until every copy carries it.
 */
class LabReferenceRangeFieldCopyGuardTest {

    private static LabTestReferenceRange everyFieldSet() throws IllegalAccessException {
        LabTestReferenceRange range = new LabTestReferenceRange();
        int n = 1;
        for (Field f : instanceFields(LabTestReferenceRange.class)) {
            f.setAccessible(true);
            if (f.getType() == Double.class) {
                f.set(range, 10.0 + n);
            } else if (f.getType() == Integer.class) {
                f.set(range, n);
            } else if (f.getType() == String.class) {
                f.set(range, "v" + n);
            } else {
                throw new AssertionError("Unhandled field type on the range: " + f);
            }
            n++;
        }
        return range;
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                out.add(f);
            }
        }
        return out;
    }

    private static Map<String, Object> valuesOf(Object bean, List<String> names) throws ReflectiveOperationException {
        Map<String, Object> out = new TreeMap<>();
        for (String name : names) {
            Field f = bean.getClass().getDeclaredField(name);
            f.setAccessible(true);
            out.put(name, f.get(bean));
        }
        return out;
    }

    private static List<String> rangeFieldNames() {
        return instanceFields(LabTestReferenceRange.class).stream().map(Field::getName).toList();
    }

    @Test
    void everyPayloadDeclaresEveryRangeField() {
        for (Class<?> payload : List.of(LabTestReferenceRangeDTO.class, LabResultReferenceRangeDTO.class)) {
            List<String> declared = Arrays.stream(payload.getDeclaredFields()).map(Field::getName).toList();
            assertThat(declared).as(payload.getSimpleName()).containsAll(rangeFieldNames());
        }
    }

    @Test
    void theResultPayloadCarriesEveryField() throws ReflectiveOperationException {
        LabTestReferenceRange stored = everyFieldSet();

        LabResultReferenceRangeDTO copy = LabResultMapper.toReferenceRangeDto(stored);

        assertThat(valuesOf(copy, rangeFieldNames())).isEqualTo(valuesOf(stored, rangeFieldNames()));
    }

    @Test
    void theDefinitionRoundTripCarriesEveryField() throws ReflectiveOperationException {
        LabTestReferenceRange stored = everyFieldSet();
        LabTestDefinitionMapper mapper = new LabTestDefinitionMapper();
        LabTestDefinition definition = new LabTestDefinition();
        definition.setReferenceRanges(new ArrayList<>(List.of(stored)));

        List<LabTestReferenceRangeDTO> shown = mapper.toDto(definition).getReferenceRanges();
        assertThat(shown).hasSize(1);
        assertThat(valuesOf(shown.get(0), rangeFieldNames())).isEqualTo(valuesOf(stored, rangeFieldNames()));

        List<LabTestReferenceRange> saved = mapper.mapRangeDtosToEntities(shown);
        assertThat(saved).containsExactly(stored);
    }
}
