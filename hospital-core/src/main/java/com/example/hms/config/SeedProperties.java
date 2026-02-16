package com.example.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.seed")
public class SeedProperties {

    /**
     * Master switch for data seeders (default true to preserve current behavior).
     */
    private boolean enabled = true;

    private final Synthetic synthetic = new Synthetic();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Synthetic getSynthetic() {
        return synthetic;
    }

    public static class Synthetic {

        /**
         * Enables synthetic data generation seeders (default true).
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
