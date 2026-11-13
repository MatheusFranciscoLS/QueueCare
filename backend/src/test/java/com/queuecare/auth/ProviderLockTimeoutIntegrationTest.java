package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Espera LIMITADA pelo bloqueio da linha do prestador: se outra transação segura a linha por mais que o limite, a API
 * responde 409 RESOURCE_BUSY (nunca 500, nunca espera infinita), e a operação funciona assim que o bloqueio cai.
 */
@TestPropertySource(properties = "queuecare.scheduling.provider-lock-timeout=400ms")
class ProviderLockTimeoutIntegrationTest extends AuthIntegrationTestBase {

	@Autowired
	DataSource dataSource;

	private String providerToken() throws Exception {
		seedUser("p1@exemplo.test", "PROVIDER", true);
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "p1@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions send(MockHttpServletRequestBuilder builder, String bearer, String body) throws Exception {
		return mvc.perform(builder.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static final String BUSINESS = "{\"businessName\":\"Oficina\",\"timeZone\":\"America/Sao_Paulo\",\"minimumNoticeMinutes\":0,\"cancellationDeadlineMinutes\":0}";
	private static final String WINDOWS = "[{\"dayOfWeek\":\"MONDAY\",\"start\":\"08:00\",\"end\":\"12:00\"}]";

	@Test
	void bloqueioSegurandoALinhaViraConflito409RapidoESoltaDepois() throws Exception {
		String token = providerToken();
		send(post("/api/provider"), token, BUSINESS).andExpect(status().isCreated());

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (var stmt = holder.createStatement()) {
				stmt.execute("SELECT id FROM providers FOR UPDATE"); // outra transação segura a linha
			}

			long start = System.nanoTime();
			send(put("/api/provider/availability"), token, WINDOWS).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
			assertTrue(elapsedMillis < 5_000, "a espera é limitada (~400 ms), levou " + elapsedMillis + " ms");

			send(put("/api/provider"), token, BUSINESS).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			holder.rollback();
		}

		send(put("/api/provider/availability"), token, WINDOWS).andExpect(status().isOk());
		assertEquals(1L, count("SELECT count(*) FROM availability_windows"), "nada ficou pela metade");
		assertEquals("0", jdbc.queryForObject("SHOW lock_timeout", String.class), "o limite vale só dentro da transação, não vaza para a conexão do pool");
	}
}
