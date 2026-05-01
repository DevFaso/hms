package com.example.hms.enums;

/**
 * Type of media attached to a chat message in a low-bandwidth telehealth
 * consult. Constrained at the database CHECK level (V68) and in
 * FileUploadService content-type allowlists.
 */
public enum ChatAttachmentKind {
    PHOTO,
    AUDIO
}
