package com.example.hms.hl7.mllp;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MllpFieldWidthExceptionTest {
    @Test void structuredDisplayArgumentsDoNotChangeTheProtocolDiagnostic() {
        String diagnostic = "MSH-10 exceeds 255 characters";
        var structured = new MllpFieldWidthException(diagnostic, null, "MSH-10", 255);
        assertThat(structured.getMessage()).isEqualTo(diagnostic);
        assertThat(structured.fieldName()).isEqualTo("MSH-10");
        assertThat(structured.maximum()).isEqualTo(255);
        assertThat(structured.replyHeader()).isNull();
        var legacy = new MllpFieldWidthException(diagnostic, null);
        assertThat(legacy.getMessage()).isEqualTo(diagnostic);
        assertThat(legacy.fieldName()).isNull();
        assertThat(legacy.maximum()).isNull();
        assertThat(new MllpFieldWidthException(diagnostic).getMessage()).isEqualTo(diagnostic);
    }
}
