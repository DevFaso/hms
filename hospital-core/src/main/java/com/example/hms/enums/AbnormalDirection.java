package com.example.hms.enums;

/**
 * Which side of the reference range an abnormal result crossed. Carried
 * next to the three-value {@link AbnormalFlag} family on staff-facing
 * DTOs, so a UI that only knows NORMAL / ABNORMAL / CRITICAL keeps
 * working and one that wants the arrow can draw it.
 */
public enum AbnormalDirection {
    LOW,
    HIGH
}
