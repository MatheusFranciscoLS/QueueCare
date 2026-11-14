package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

/** O comando encerra com o código certo e NUNCA escreve e-mail ou senha em log. */
class AdminBootstrapRunnerTest {

	private static final String EMAIL = "dono-secreto@exemplo.test";
	private static final String PASSWORD = "senha-super-secreta-123";

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(AdminBootstrapRunner.class);
	private final List<Integer> exits = new ArrayList<>();

	@BeforeEach
	void attach() {
		logger.setLevel(Level.ALL);
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(appender);
	}

	/** Serviço que devolve o resultado dado ou lança a exceção dada; a mensagem da exceção ECOA a senha de propósito. */
	private static AdminBootstrapService service(AdminBootstrapService.Result result, RuntimeException failure) {
		return new AdminBootstrapService(null, null, null, null) {
			@Override
			public Result bootstrap(String email, String password) {
				if (failure != null) {
					throw failure;
				}
				return result;
			}
		};
	}

	private String allLogs() {
		StringBuilder sb = new StringBuilder();
		for (ILoggingEvent e : appender.list) {
			sb.append(e.getFormattedMessage()).append('\n');
			if (e.getThrowableProxy() != null) {
				sb.append(e.getThrowableProxy().getMessage());
			}
		}
		return sb.toString();
	}

	@Test
	void criadoEJaExistenteSaemComCodigoZero() {
		new AdminBootstrapRunner(service(AdminBootstrapService.Result.CREATED, null), EMAIL, PASSWORD, exits::add).run(new DefaultApplicationArguments());
		new AdminBootstrapRunner(service(AdminBootstrapService.Result.ALREADY_EXISTS, null), EMAIL, PASSWORD, exits::add).run(new DefaultApplicationArguments());

		assertEquals(List.of(0, 0), exits);
	}

	@Test
	void erroDeConfiguracaoSaiComCodigoUmSemEcoarOsValores() {
		new AdminBootstrapRunner(service(null, new AdminBootstrapService.BootstrapException("ADMIN_PASSWORD fora da política")), EMAIL, PASSWORD, exits::add)
				.run(new DefaultApplicationArguments());

		assertEquals(List.of(1), exits);
		assertTrue(allLogs().contains("fora da política"));
		assertFalse(allLogs().contains(PASSWORD) || allLogs().contains(EMAIL));
	}

	@Test
	void falhaInesperadaLogaSoOTipoNuncaAMensagemQuePodeTerADadosDaEntrada() {
		new AdminBootstrapRunner(service(null, new IllegalStateException("erro com " + EMAIL + " e " + PASSWORD)), EMAIL, PASSWORD, exits::add)
				.run(new DefaultApplicationArguments());

		assertEquals(List.of(1), exits);
		assertTrue(allLogs().contains("IllegalStateException"));
		assertFalse(allLogs().contains(PASSWORD) || allLogs().contains(EMAIL), allLogs());
	}

	@Test
	void sucessoTambemNaoEscreveEmailNemSenha() {
		new AdminBootstrapRunner(service(AdminBootstrapService.Result.CREATED, null), EMAIL, PASSWORD, exits::add).run(new DefaultApplicationArguments());

		assertFalse(allLogs().contains(PASSWORD) || allLogs().contains(EMAIL), allLogs());
	}
}
