package com.example.hms.architecture;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1537 review (round 4): Prescription declared a package-private {@code @PreUpdate void onUpdate()},
 * the same name and package as BaseEntity's callback, so it overrode it and {@code updatedAt} stopped
 * moving on every prescription update (the FHIR {@code _since} exports and delta syncs missed changes).
 * No entity under BaseEntity may declare a method with the name and parameters of one of BaseEntity's
 * lifecycle callbacks.
 */
@DisplayName("D1537 review: no entity shadows a BaseEntity lifecycle callback")
class EntityCallbackShadowingGuardTest {

    private static final List<Class<? extends Annotation>> CALLBACKS = List.of(
        PrePersist.class, PreUpdate.class, PreRemove.class, PostPersist.class, PostUpdate.class,
        PostRemove.class, PostLoad.class);

    @Test
    @DisplayName("an entity under BaseEntity declares no method named as one of BaseEntity's callbacks")
    void noEntityShadowsABaseEntityCallback() throws ClassNotFoundException {
        Set<String> callbackNames = new TreeSet<>();
        for (Method method : BaseEntity.class.getDeclaredMethods()) {
            if (CALLBACKS.stream().anyMatch(method::isAnnotationPresent)) {
                callbackNames.add(method.getName() + "/" + method.getParameterCount());
            }
        }
        assertThat(callbackNames).as("BaseEntity's callbacks are found, or the scan checks nothing")
            .contains("onCreate/0", "onUpdate/0");

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BaseEntity.class));
        List<String> shadows = new ArrayList<>();
        int entities = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            entities++;
            for (Class<?> c = type; c != null && c != BaseEntity.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (callbackNames.contains(method.getName() + "/" + method.getParameterCount())) {
                        shadows.add(c.getName() + "#" + method.getName());
                    }
                }
            }
        }
        assertThat(entities).as("entities under BaseEntity are found").isGreaterThan(10);
        assertThat(shadows).as("rename these: they override BaseEntity's callback, and createdAt/updatedAt stop being stamped")
            .isEmpty();
    }
}
