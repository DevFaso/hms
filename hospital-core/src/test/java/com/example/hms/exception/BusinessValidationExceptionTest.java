package com.example.hms.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BusinessValidationExceptionTest {

    @Test
    @DisplayName("constructor sets message correctly")
    void constructorSetsMessage() {
        BusinessValidationException ex = new BusinessValidationException("Validation failed");
        assertEquals("Validation failed", ex.getMessage());
    }

    @Test
    @DisplayName("constructor with null message")
    void constructorWithNullMessage() {
        BusinessValidationException ex = new BusinessValidationException(null);
        assertNull(ex.getMessage());
    }

    @Test
    @DisplayName("constructor with empty message")
    void constructorWithEmptyMessage() {
        BusinessValidationException ex = new BusinessValidationException("");
        assertEquals("", ex.getMessage());
    }

    @Test
    @DisplayName("is a RuntimeException")
    void isRuntimeException() {
        BusinessValidationException ex = new BusinessValidationException("test");
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("is throwable and catchable as BusinessValidationException")
    void throwAndCatch() {
        assertThrows(BusinessValidationException.class, () -> {
            throw new BusinessValidationException("boom");
        });
    }

    @Test
    @DisplayName("can be caught as RuntimeException")
    void caughtAsRuntime() {
        assertThrows(RuntimeException.class, () -> {
            throw new BusinessValidationException("runtime catch");
        });
    }

    @Test
    @DisplayName("@ResponseStatus annotation present with BAD_REQUEST")
    void responseStatusAnnotation() {
        ResponseStatus annotation = BusinessValidationException.class.getAnnotation(ResponseStatus.class);
        assertNotNull(annotation, "@ResponseStatus should be present");
        assertEquals(HttpStatus.BAD_REQUEST, annotation.value());
    }

    @Test
    @DisplayName("no cause is set")
    void noCause() {
        BusinessValidationException ex = new BusinessValidationException("detail");
        assertNull(ex.getCause());
    }
}
