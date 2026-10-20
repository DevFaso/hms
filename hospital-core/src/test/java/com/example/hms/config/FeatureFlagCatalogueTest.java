package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D310/D422: the deployed environments declared no flag at all, so the
 * super-admin list was empty and the D245 known-key guard refused every
 * create. The catalogue lives in the base application.properties, which every
 * profile loads; this binds that exact file the way Spring does.
 */
class FeatureFlagCatalogueTest {

    @Test
    @DisplayName("the base config, loaded by every profile, declares the platform flag catalogue")
    void baseConfigDeclaresTheCatalogue() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new PropertiesPropertySourceLoader()
            .load("application", new ClassPathResource("application.properties"))
            .forEach(env.getPropertySources()::addLast);
        FeatureFlagProperties props = new Binder(ConfigurationPropertySources.get(env))
            .bind("app.feature-flags", FeatureFlagProperties.class)
            .orElseGet(FeatureFlagProperties::new);

        assertThat(props.getDefaultsOrEmpty())
            .containsKeys("ff.platform.billing", "ff.platform.ehr", "ff.platform.inventory");
    }
}
