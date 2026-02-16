package com.example.hms.utility;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class MessageUtil {

    private static MessageSource messageSource;

    @SuppressWarnings({"java:S1118", "java:S3010"})
    public MessageUtil(MessageSource messageSource) {
        MessageUtil.messageSource = messageSource;
    }

    public static void setMessageSource(MessageSource ms) {
        messageSource = ms;
    }

    public static String resolve(String key, Object... args) {
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (RuntimeException e) {
            // Graceful fallback
            return "[Missing translation] " + key + (args.length > 0 ? " - " + args[0] : "");
        }
    }
}
