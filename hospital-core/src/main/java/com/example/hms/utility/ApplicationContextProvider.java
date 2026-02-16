package com.example.hms.utility;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class ApplicationContextProvider implements ApplicationContextAware {
    private static ApplicationContext context;

    @Override
    @SuppressWarnings("java:S2696")
    public void setApplicationContext(@org.springframework.lang.NonNull ApplicationContext ctx) {
        context = ctx;
    }

    public static ApplicationContext getApplicationContext() {
        return context;
    }
}
