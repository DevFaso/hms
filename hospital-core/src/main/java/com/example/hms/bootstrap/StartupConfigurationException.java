package com.example.hms.bootstrap;

/**
 * The application's configuration does not allow it to start. Thrown while the environment or the
 * context is being prepared, before any request is served: its message is read by the operator in
 * the start-up log, never by a client, so it is plain English rather than a message-bundle key.
 */
public class StartupConfigurationException extends IllegalStateException {

    public StartupConfigurationException(String message) {
        super(message);
    }
}
