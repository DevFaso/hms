package com.example.hms.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.junit.jupiter.api.Assertions.*;

class BadRequestExceptionTest {

    @Test
    @DisplayName("constructor sets message correctly")
    void constructorSetsMessage() {
        BadRequestException ex = new BadRequestException("Invalid input");
        assertEquals("Invalid input", ex.getMessage());
    }

    @Test
    @DisplayName("constructor with null message")
    void constructorWithNullMessage() {
        BadRequestException ex = new BadRequestException(null);
        assertNull(ex.getMessage());
    }

    @Test
    @DisplayName("constructor with empty message")
    void constructorWithEmptyMessage() {
        BadRequestException ex = new BadRequestException("");
        assertEquals("", ex.getMessage());
    }

    @Test
    @DisplayName("is a RuntimeException")
    void isRuntimeException() {
        BadRequestException ex = new BadRequestException("test");
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("is throwable and catchable")
    void isThrowableAndCatchable() {
        assertThrows(BadRequestException.class, () -> {
            throw new BadRequestException("boom");
        });
    }

    @Test
    @DisplayName("can be caught as RuntimeException")
    void caughtAsRuntimeException() {
        assertThrows(RuntimeException.class, () -> {
            throw new BadRequestException("catch me");
        });
    }

    @Test
    @DisplayName("@ResponseStatus annotation present with BAD_REQUEST")
    void responseStatusAnnotation() {
        ResponseStatus annotation = BadRequestException.class.getAnnotation(ResponseStatus.class);
        assertNotNull(annotation, "@ResponseStatus should be present");
        assertEquals(HttpStatus.BAD_REQUEST, annotation.value());
    }

    @Test
    @DisplayName("message is preserved through getCause chain")
    void messagePreservedInStack() {
        BadRequestException ex = new BadRequestException("detail");
        assertEquals("detail", ex.getMessage());
        assertNull(ex.getCause());
    }
}
