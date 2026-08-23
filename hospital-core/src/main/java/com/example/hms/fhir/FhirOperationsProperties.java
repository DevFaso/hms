package com.example.hms.fhir;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flags for FHIR R4 named operations (roadmap rows 21 + 22).
 *
 * <p>Two independent switches under {@code app.fhir.operations.*}:
 * <ul>
 *   <li>{@code bulk-export.enabled} — gates {@code $export} (system /
 *       Patient level — roadmap row 21). Default {@code false}.
 *       When off, every {@code $export} call returns
 *       {@code 501 Not Implemented} + a FHIR {@code OperationOutcome}
 *       and the {@code CapabilityStatement} omits the operation entry.</li>
 *   <li>{@code everything.enabled} — gates {@code Patient/{id}/$everything}
 *       (roadmap row 22). Default {@code false}. Flag-off surfaces as
 *       {@code 405 Method Not Allowed} (the HMS flag-off contract on
 *       synchronous FHIR paths).</li>
 * </ul>
 *
 * <p>Both are intentionally separate so an operator can promote
 * {@code $everything} to production (synchronous, low-risk) without
 * also enabling {@code $export} (async, disk-bound, capacity-sensitive).
 */
@ConfigurationProperties(prefix = "app.fhir.operations")
public class FhirOperationsProperties {

    private final BulkExport bulkExport = new BulkExport();
    private final Everything everything = new Everything();

    public BulkExport getBulkExport() {
        return bulkExport;
    }

    public Everything getEverything() {
        return everything;
    }

    public static class BulkExport {
        private boolean enabled = false;

        /**
         * Where the runner writes NDJSON output ({@code {dir}/{jobId}/}).
         * A sibling of the public upload tree by default — never inside
         * it, because {@code /uploads/**} is served permitAll and export
         * output is a mass PHI extract.
         */
        private String storageDir = "fhir-bulk-exports";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStorageDir() {
            return storageDir;
        }

        public void setStorageDir(String storageDir) {
            this.storageDir = storageDir;
        }
    }

    public static class Everything {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
