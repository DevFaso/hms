package com.example.hms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class HmsApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts successfully
    }
}
