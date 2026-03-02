package com.example.hms.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("ConflictException")
class ConflictExceptionTest {

    // =========================================================================
    // Construction & message
    // =========================================================================

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("stores a plain message verbatim")
        void storesPlainMessage() {
            ConflictException ex = new ConflictException("Something already exists.");
            assertThat(ex.getMessage()).isEqualTo("Something already exists.");
        }

        @Test
        @DisplayName("stores a field-prefixed message verbatim")
        void storesFieldPrefixedMessage() {
            ConflictException ex = new ConflictException("email:Email 'a@b.com' is already registered.");
            assertThat(ex.getMessage()).isEqualTo("email:Email 'a@b.com' is already registered.");
        }

        @Test
        @DisplayName("stores null message without NPE")
        void storesNullMessage() {
            ConflictException ex = new ConflictException(null);
            assertThat(ex.getMessage()).isNull();
        }

        @Test
        @DisplayName("stores empty message")
        void storesEmptyMessage() {
            ConflictException ex = new ConflictException("");
            assertThat(ex.getMessage()).isEmpty();
        }
    }

    // =========================================================================
    // Inheritance
    // =========================================================================

    @Nested
    @DisplayName("class hierarchy")
    class ClassHierarchy {

        @Test
        @DisplayName("is a RuntimeException")
        void isRuntimeException() {
            assertInstanceOf(RuntimeException.class, new ConflictException("test"));
        }

        @Test
        @DisplayName("is throwable and catchable as ConflictException")
        void throwAndCatchAsSelf() {
            assertThatThrownBy(() -> { throw new ConflictException("boom"); })
                .isInstanceOf(ConflictException.class)
                .hasMessage("boom");
        }

        @Test
        @DisplayName("can be caught as RuntimeException")
        void catchableAsRuntimeException() {
            assertThatThrownBy(() -> { throw new ConflictException("runtime"); })
                .isInstanceOf(RuntimeException.class);
        }
    }

    // =========================================================================
    // @ResponseStatus annotation
    // =========================================================================

    @Nested
    @DisplayName("@ResponseStatus")
    class ResponseStatusAnnotation {

        @Test
        @DisplayName("is annotated with @ResponseStatus(CONFLICT)")
        void hasResponseStatusConflict() {
            ResponseStatus annotation = ConflictException.class.getAnnotation(ResponseStatus.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
