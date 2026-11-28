package com.example.hms.mapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA r81: the audit search showed the writer's placeholder as a resource id. */
class AuditEventLogMapperResourceIdTest {

    @Test
    void thePlaceholderIsNotAnId() {
        assertThat(AuditEventLogMapper.shownResourceId("Unknown Resource")).isNull();
        assertThat(AuditEventLogMapper.shownResourceId(" ")).isNull();
        assertThat(AuditEventLogMapper.shownResourceId(null)).isNull();
    }

    @Test
    void aRealIdIsKept() {
        assertThat(AuditEventLogMapper.shownResourceId("7f1c0d5e-0000-4000-8000-000000000001"))
            .isEqualTo("7f1c0d5e-0000-4000-8000-000000000001");
    }
}
