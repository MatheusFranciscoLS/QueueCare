package com.queuecare.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.queuecare.scheduling.application.ProviderAdminService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Configuração inválida IMPEDE a aplicação de subir, em vez de desligar a proteção em silêncio. */
class RateLimitPropertiesValidationTest {

	@Configuration
	@EnableConfigurationProperties(RateLimitProperties.class)
	static class PropertiesOnly {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

	@Test
	void padroesSaoValidos() {
		runner.run(context -> {
			assertNull(context.getStartupFailure());
			RateLimitProperties properties = context.getBean(RateLimitProperties.class);
			assertEquals(15, properties.loginFailureWindowMinutes());
			assertEquals(5, properties.loginFailuresPerEmailAndIp());
		});
	}

	@Test
	void janelaNegativaOuZeroImpedeAInicializacao() {
		for (String value : new String[] {"-5", "0"}) {
			runner.withPropertyValues("queuecare.rate-limit.login-failure-window-minutes=" + value)
					.run(context -> assertNotNull(context.getStartupFailure(), "janela " + value + " deveria ser recusada"));
		}
	}

	@Test
	void limitesNaoPositivosOuAbsurdosImpedemAInicializacao() {
		for (String property : new String[] {"login-per-minute=0", "register-per-minute=-1", "slots-per-minute=0",
				"login-failures-per-email-and-ip=0", "password-change-attempts=0", "password-change-attempts=-3", "login-per-minute=2000000", "login-failure-window-minutes=99999"}) {
			runner.withPropertyValues("queuecare.rate-limit." + property)
					.run(context -> assertNotNull(context.getStartupFailure(), property + " deveria ser recusado"));
		}
	}

	@Test
	void esperaDoBloqueioDoPrestadorPrecisaEstarEntre1msE60s() {
		for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofNanos(1), Duration.ofNanos(500_000), Duration.ofNanos(999_999), Duration.ofSeconds(-1),
				Duration.ofSeconds(60).plusNanos(1), Duration.ofSeconds(61)}) {
			assertThrows(IllegalArgumentException.class, () -> new ProviderAdminService(null, null, null, null, invalid), invalid.toString());
		}
		for (Duration valid : new Duration[] {Duration.ofMillis(1), Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofSeconds(60)}) {
			assertNotNull(new ProviderAdminService(null, null, null, null, valid), valid.toString());
		}
	}
}
