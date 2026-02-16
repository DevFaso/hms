package com.example.hms;

import com.example.hms.config.TestPostgresConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
public abstract class BaseIT { }
