package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.queuecare.security.RequestIdFilter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * Log de acesso (ADR 0036): uma linha por chamada com método, ROTA (o padrão, nunca o caminho real), status e duração, junto do código da requisição. O que NÃO pode aparecer
 * é tão importante quanto o que aparece: identificadores, texto de busca, cabeçalhos e token ficam de fora.
 */
class AccessLogIntegrationTest extends AuthIntegrationTestBase {

	private Logger logger;
	private ListAppender<ILoggingEvent> lines;
	private String ownerToken;
	private String customerToken;

	@BeforeEach
	void attach() {
		logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.ACCESS_LOGGER);
		logger.setLevel(Level.INFO);
		lines = new ListAppender<>();
		lines.start();
		logger.addAppender(lines);
		UUID owner = seedUser("log-owner@exemplo.test", "PROVIDER", true);
		UUID customer = seedUser("log-cust@exemplo.test", "CUSTOMER", true);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, 'Estúdio Log', 'America/Sao_Paulo', 0, 120)", owner);
		ownerToken = "Bearer " + validToken(owner);
		customerToken = "Bearer " + validToken(customer);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(lines);
	}

	private List<String> messages() {
		return lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Test
	void umaLinhaPorChamadaComMetodoRotaStatusEDuracao() throws Exception {
		String requestId = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, ownerToken)).andReturn().getResponse().getHeader(RequestIdFilter.HEADER);

		assertEquals(1, lines.list.size());
		assertTrue(Pattern.matches("GET /api/me -> 200 em \\d+ ms", messages().get(0)), messages().get(0));
		assertEquals(requestId, lines.list.get(0).getMDCPropertyMap().get(RequestIdFilter.MDC_KEY), "a linha leva o código da requisição, o mesmo da resposta");
	}

	@Test
	void aRotaEOPadraoENuncaOIdentificadorNemOTextoDeBusca() throws Exception {
		UUID someone = UUID.randomUUID();
		mvc.perform(get("/api/customer/pets/" + someone).header(HttpHeaders.AUTHORIZATION, customerToken));
		mvc.perform(get("/api/agenda/customers").param("q", "textoSigiloso").header(HttpHeaders.AUTHORIZATION, ownerToken));

		List<String> all = messages();
		assertTrue(all.get(0).startsWith("GET /api/customer/pets/{id} -> 404"), all.get(0));
		assertTrue(all.get(1).startsWith("GET /api/agenda/customers -> 200"), all.get(1));
		String everything = String.join("\n", all);
		assertFalse(everything.contains(someone.toString()), "o identificador real não vai para o log");
		assertFalse(everything.contains("textoSigiloso"), "o texto buscado (pode ser nome ou telefone de alguém) não vai para o log");
	}

	@Test
	void recusaDosFiltrosAparecemSemRotaENuncaComOToken() throws Exception {
		mvc.perform(get("/api/me")); // sem login: 401 antes de qualquer controlador
		mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, customerToken)); // 403

		List<String> all = messages();
		assertTrue(all.get(0).startsWith("GET (sem rota) -> 401"), all.get(0));
		assertTrue(all.get(1).startsWith("GET (sem rota) -> 403"), all.get(1));
		String everything = String.join("\n", all) + lines.list.stream().map(Object::toString).reduce("", String::concat);
		assertFalse(everything.contains(customerToken.substring("Bearer ".length(), "Bearer ".length() + 20)), "o token nunca vai para o log");
		assertFalse(everything.contains("Bearer"), "nem o cabeçalho de autorização");
	}

	@Test
	void verificacoesDeSaudeNaoViramRuido() throws Exception {
		mvc.perform(get("/actuator/health/liveness"));
		mvc.perform(get("/actuator/health"));
		assertTrue(lines.list.isEmpty(), "o Render e o monitor chamam a saúde o tempo todo: ficam de fora do log de acesso");
	}
}
