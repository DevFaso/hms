package com.example.hms.enums;

/**
 * Identifies the kind of actor that triggered an audit event.
 * <ul>
 *   <li>{@link #USER} — a real, authenticated user (user_id is required).</li>
 *   <li>{@link #SYSTEM} — a system/bootstrap process (user_id is null).</li>
 * </ul>
 */
public enum ActorType {
    USER,
    SYSTEM
}
