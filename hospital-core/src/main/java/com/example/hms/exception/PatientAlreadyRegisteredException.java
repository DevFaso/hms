package com.example.hms.exception;

// PatientAlreadyRegisteredException.java
public class PatientAlreadyRegisteredException extends RuntimeException {
    public PatientAlreadyRegisteredException(String message) {
        super(message);
    }
}
