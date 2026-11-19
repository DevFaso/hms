package com.example.hms.imaging.dicom;

import com.example.hms.model.Hospital;
import org.springframework.stereotype.Component;

/**
 * Which PACS serves a hospital's images.
 *
 * <ol>
 *   <li>The platform flag {@code app.imaging.dicom-proxy.enabled} is the master
 *       switch: off, nothing is reached ({@link State#PROXY_DISABLED}).</li>
 *   <li>The hospital's own PACS (V317) when its admin switched it on and gave a
 *       DICOMweb URL: {@link PacsConnection.Source#HOSPITAL}, with its own
 *       credentials.</li>
 *   <li>Otherwise the platform fallback {@code app.imaging.dicom-proxy.base-url}
 *       with the platform credentials: {@link PacsConnection.Source#PLATFORM}.</li>
 *   <li>Neither: {@link State#NOT_CONFIGURED}.</li>
 * </ol>
 */
@Component
public class PacsConnectionResolver {

    /** Whether a hospital's images can be reached. */
    public enum State { READY, PROXY_DISABLED, NOT_CONFIGURED }

    /** The outcome: a connection only when {@link State#READY}. */
    public record Resolution(State state, PacsConnection connection) {
        public boolean ready() {
            return state == State.READY && connection != null;
        }
    }

    private final DicomProxyProperties properties;

    public PacsConnectionResolver(DicomProxyProperties properties) {
        this.properties = properties;
    }

    public boolean platformEnabled() {
        return properties.isEnabled();
    }

    public boolean platformFallbackConfigured() {
        return PacsUrlPolicy.normalise(properties.getBaseUrl()) != null;
    }

    public boolean allowPrivateHosts() {
        return properties.isAllowPrivateHosts();
    }

    public Resolution resolve(Hospital hospital) {
        if (!properties.isEnabled()) {
            return new Resolution(State.PROXY_DISABLED, null);
        }
        String own = hospital == null ? null : PacsUrlPolicy.normalise(hospital.getPacsDicomwebBaseUrl());
        if (hospital != null && hospital.isPacsEnabled() && own != null) {
            return new Resolution(State.READY, new PacsConnection(own, blankToEmpty(hospital.getPacsAuthUsername()),
                blankToEmpty(hospital.getPacsAuthPassword()), PacsConnection.Source.HOSPITAL));
        }
        String platform = PacsUrlPolicy.normalise(properties.getBaseUrl());
        if (platform != null) {
            return new Resolution(State.READY, new PacsConnection(platform, blankToEmpty(properties.getUsername()),
                blankToEmpty(properties.getPassword()), PacsConnection.Source.PLATFORM));
        }
        return new Resolution(State.NOT_CONFIGURED, null);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
