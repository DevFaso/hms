package com.example.hms.mapper.empi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmpiMapperPlaceholderTest {

    @Test
    void privateConstructorThrowsIllegalStateException() throws Exception {
        Constructor<EmpiMapperPlaceholder> ctor = EmpiMapperPlaceholder.class.getDeclaredConstructor();
        ctor.setAccessible(true);

        assertThatThrownBy(ctor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Use com.example.hms.mapper.EmpiMapper instead");
    }

    @Test
    void classIsNotPublic() {
        assertThat(java.lang.reflect.Modifier.isPublic(EmpiMapperPlaceholder.class.getModifiers()))
                .isFalse();
    }

    @Test
    void constructorIsPrivate() throws Exception {
        Constructor<EmpiMapperPlaceholder> ctor = EmpiMapperPlaceholder.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())).isTrue();
    }
}
