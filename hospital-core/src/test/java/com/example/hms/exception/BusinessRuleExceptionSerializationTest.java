package com.example.hms.exception;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRuleExceptionSerializationTest {

    @Test
    void deserializedRefusalRetainsItsKeyWithoutTransientArguments() throws Exception {
        BusinessRuleException original = BusinessRuleException.ofKey("staff.license.duplicate", new Object());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }

        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            BusinessRuleException restored = (BusinessRuleException) input.readObject();
            assertThat(restored.getMessageKey()).isEqualTo(original.getMessageKey());
            assertThat(restored.getMessage()).isEqualTo(original.getMessage());
            assertThat(restored.getArgs()).isEmpty();
        }
    }
}
