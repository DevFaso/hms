package com.example.hms.enums;

public enum AbnormalFlag {
    /** Result within reference range. */
    NORMAL,
    /** Result outside reference range but not immediately life-threatening. */
    ABNORMAL,
    /** Result at a life-threatening level requiring immediate clinical action. */
    CRITICAL
}
