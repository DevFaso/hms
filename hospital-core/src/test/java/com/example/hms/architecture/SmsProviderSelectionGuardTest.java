package com.example.hms.architecture;

import com.example.hms.service.IkoddiSmsServiceImpl;
import com.example.hms.service.MockSmsServiceImpl;
import com.example.hms.service.SmsService;
import com.example.hms.service.integration.IkoddiGateway;
import com.example.hms.service.integration.IkoddiGatewayImpl;
import com.example.hms.service.integration.OrangeSmsGatewayImpl;
import com.example.hms.service.integration.SmsRecipientAllowlist;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Orange is the SMS provider unless {@code app.sms.provider=ikoddi}, and there
 * is no side door to IKODDI. Every caller holds the {@link IkoddiGateway}
 * interface (or {@link SmsService}); only the Orange gateway itself holds the
 * IKODDI implementation, to verify codes IKODDI issued before the switch. A
 * class injecting {@code IkoddiGatewayImpl} directly would keep texting through
 * IKODDI after the switch, silently, and is refused here.
 */
@DisplayName("SMS provider selection: Orange by default, no direct IKODDI path")
class SmsProviderSelectionGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern IKODDI_IMPL = Pattern.compile("\\bIkoddiGatewayImpl\\b");

    @Configuration
    @Import({IkoddiGatewayImpl.class, OrangeSmsGatewayImpl.class, IkoddiSmsServiceImpl.class, MockSmsServiceImpl.class})
    static class SmsBeans {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SmsBeans.class);

    @Test
    @DisplayName("no main class but the Orange gateway refers to the IKODDI implementation")
    void noDirectIkoddiPath() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("IkoddiGatewayImpl.java") || name.equals("OrangeSmsGatewayImpl.java")) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (IKODDI_IMPL.matcher(code).find()) {
                    offenders.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(offenders).as("inject IkoddiGateway (the interface), never IkoddiGatewayImpl").isEmpty();
    }

    @Test
    @DisplayName("by default the gateway every caller receives is Orange, and SMS stays on the mock until Orange has credentials")
    void orangeByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IkoddiGateway.class)).isInstanceOf(OrangeSmsGatewayImpl.class);
            assertThat(context.getBean(IkoddiGateway.class).isConfigured()).isFalse();
            assertThat(context.getBean(SmsService.class)).isInstanceOf(MockSmsServiceImpl.class);
        });
    }

    @Test
    @DisplayName("IKODDI credentials alone do not make IKODDI send while Orange is selected")
    void ikoddiCredentialsDoNotBypassOrange() {
        runner.withPropertyValues("app.ikoddi.enabled=true", "app.ikoddi.api-key=k", "app.ikoddi.organization-id=o",
                "app.ikoddi.otp-app-id=a")
            .run(context -> {
                assertThat(context.getBean(IkoddiGateway.class)).isInstanceOf(OrangeSmsGatewayImpl.class);
                assertThat(context.getBean(SmsService.class)).isInstanceOf(MockSmsServiceImpl.class);
            });
    }

    @Test
    @DisplayName("with Orange credentials the real SmsService is backed by the Orange gateway")
    void orangeConfigured() {
        runner.withPropertyValues("app.sms.provider=orange", "app.orange-sms.client-id=id",
                "app.orange-sms.client-secret=secret")
            .run(context -> {
                assertThat(context.getBean(IkoddiGateway.class)).isInstanceOf(OrangeSmsGatewayImpl.class);
                assertThat(context.getBean(IkoddiGateway.class).isConfigured()).isTrue();
                assertThat(context.getBean(SmsService.class)).isInstanceOf(IkoddiSmsServiceImpl.class);
                assertThat(context.getBean(SmsService.class).deliversRealSms()).isTrue();
            });
    }

    @Test
    @DisplayName("app.sms.provider=ikoddi removes the Orange gateway and selects IKODDI explicitly")
    void ikoddiSelected() {
        runner.withPropertyValues("app.sms.provider=ikoddi", "app.orange-sms.client-id=id",
                "app.orange-sms.client-secret=secret")
            .run(context -> {
                assertThat(context).doesNotHaveBean(OrangeSmsGatewayImpl.class);
                assertThat(context.getBean(IkoddiGateway.class)).isInstanceOf(IkoddiGatewayImpl.class);
                assertThat(context.getBean(SmsService.class)).isInstanceOf(MockSmsServiceImpl.class);
            });
        runner.withPropertyValues("app.sms.provider=IKODDI", "app.ikoddi.enabled=true")
            .run(context -> assertThat(context.getBean(SmsService.class)).isInstanceOf(IkoddiSmsServiceImpl.class));
    }

    /** The body of {@code signature}'s method in {@code source}, or null when absent. */
    private static String body(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            return null;
        }
        int open = source.indexOf('{', at);
        return source.substring(open, SourceScan.closingBrace(source, open));
    }

    @Test
    @DisplayName("every SMS gateway checks the recipient allowlist in each paid send method")
    void everyGatewayChecksTheAllowlist() throws IOException {
        List<String> gateways = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!code.contains("implements IkoddiGateway")) {
                    continue;
                }
                String name = file.getFileName().toString();
                gateways.add(name);
                String sms = body(code, "public SmsDispatch sendSms(");
                if (sms == null || !sms.contains("allowlist.permits(")) {
                    offenders.add(name + "#sendSms");
                }
                String otp = body(code, "public OtpDispatch sendOtp(");
                if (otp == null || !(otp.contains("allowlist.permits(") || otp.contains("UnsupportedOperationException"))) {
                    offenders.add(name + "#sendOtp");
                }
            }
        }
        assertThat(gateways).as("the scan must find the gateways")
            .contains("OrangeSmsGatewayImpl.java", "IkoddiGatewayImpl.java");
        assertThat(offenders).as("a paid send that skips SMS_RECIPIENT_ALLOWLIST").isEmpty();
    }

    @Test
    @DisplayName("SMS_RECIPIENT_ALLOWLIST set: the injected gateway refuses an unlisted number before any request, "
        + "whichever provider is selected")
    void allowlistHoldsForEveryProvider() {
        // An unreachable base URL: if the guard were skipped, the send would fail as unreachable instead.
        runner.withPropertyValues("app.orange-sms.client-id=id", "app.orange-sms.client-secret=secret",
                "app.orange-sms.base-url=http://127.0.0.1:1", "app.sms.recipient-allowlist=+22670000001")
            .run(context -> {
                IkoddiGateway gateway = context.getBean(IkoddiGateway.class);
                assertThat(gateway).isInstanceOf(OrangeSmsGatewayImpl.class);
                assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
                    .hasMessage(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
                assertThatThrownBy(() -> context.getBean(SmsService.class).send("+22670707070", "a"))
                    .hasMessage(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
            });
        runner.withPropertyValues("app.sms.provider=ikoddi", "app.ikoddi.enabled=true", "app.ikoddi.api-key=k",
                "app.ikoddi.organization-id=o", "app.ikoddi.otp-app-id=a", "app.ikoddi.base-url=http://127.0.0.1:1",
                "app.sms.recipient-allowlist=+22670000001")
            .run(context -> {
                IkoddiGateway gateway = context.getBean(IkoddiGateway.class);
                assertThat(gateway).isInstanceOf(IkoddiGatewayImpl.class);
                assertThatThrownBy(() -> gateway.sendSms(List.of("+22670707070"), "a", null))
                    .hasMessage(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
                assertThatThrownBy(() -> gateway.sendOtp("+22670707070", IkoddiGateway.OtpChannel.SMS))
                    .hasMessage(SmsRecipientAllowlist.KEY_NOT_ALLOWLISTED);
            });
    }
}
