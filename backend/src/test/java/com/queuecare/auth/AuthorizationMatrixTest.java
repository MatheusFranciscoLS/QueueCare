package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Matriz de autorização: PERFIL x ROTA do contrato OpenAPI.
 *
 * <p>Duas garantias, uma por teste:
 * <ol>
 *   <li><b>Toda rota declara quem pode chamá-la.</b> A tabela abaixo é conferida contra {@code docs/openapi.json} nos dois sentidos: rota nova sem linha na tabela (ou linha
 *       de rota que deixou de existir) quebra o build. Esquecer de pensar na autorização de um endpoint deixa de ser possível por descuido.</li>
 *   <li><b>Cada perfil só alcança o que a tabela diz.</b> Cada rota é chamada de verdade como visitante e como cada um dos 4 perfis: o visitante recebe 401, o perfil não
 *       autorizado recebe 403 (e nunca a validação do corpo, que revelaria detalhes a quem não pode usar a rota), e o autorizado passa da autorização (qualquer resposta menos
 *       401 e 403: 400, 404 e 422 são esperados com dados de mentira).</li>
 * </ol>
 * Complementa os testes pontuais de isolamento entre usuários (um tutor nunca vê o dado de outro): aqui se prova o PERFIL; lá, o DONO do recurso.
 */
class AuthorizationMatrixTest extends AuthIntegrationTestBase {

	/** "perfis | MÉTODO /caminho". PUBLIC = qualquer um, inclusive visitante; ANY = qualquer perfil, mas com login. */
	private static final String TABLE = """
			ADMIN | POST /api/admin/providers
			ADMIN | GET /api/admin/users
			ADMIN | POST /api/admin/users/{userId}/activate
			ADMIN | POST /api/admin/users/{userId}/deactivate
			ADMIN | POST /api/admin/users/{userId}/reset-password

			PROVIDER,ATTENDANT | GET /api/agenda
			PROVIDER,ATTENDANT | GET /api/agenda/customers
			PROVIDER,ATTENDANT | POST /api/agenda/customers
			PROVIDER,ATTENDANT | POST /api/agenda/customers/{customerId}/appointments
			PROVIDER,ATTENDANT | GET /api/agenda/customers/{customerId}/pets
			PROVIDER,ATTENDANT | POST /api/agenda/customers/{customerId}/pets
			PROVIDER,ATTENDANT | GET /api/agenda/customers/{customerId}/slots
			PROVIDER,ATTENDANT | GET /api/agenda/queue
			PROVIDER,ATTENDANT | GET /api/agenda/services
			PROVIDER,ATTENDANT | GET /api/agenda/waitlist
			PROVIDER,ATTENDANT | GET /api/agenda/week
			PROVIDER,ATTENDANT | GET /api/agenda/workplace

			CUSTOMER | POST /api/appointments
			CUSTOMER | GET /api/appointments/mine
			CUSTOMER,PROVIDER,ATTENDANT | POST /api/appointments/{id}/cancel
			CUSTOMER,PROVIDER,ATTENDANT | POST /api/appointments/{id}/reschedule
			CUSTOMER,PROVIDER,ATTENDANT | GET /api/appointments/{id}/events
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/check-in
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/complete
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/deposit-received
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/no-show
			PROVIDER,ATTENDANT | GET /api/appointments/{id}/pet-visits
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/ready
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/reminded
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/start
			PROVIDER,ATTENDANT | POST /api/appointments/{id}/undo

			PUBLIC | POST /api/auth/login
			PUBLIC | POST /api/auth/register
			ANY | POST /api/auth/change-password

			CUSTOMER | GET /api/catalog/providers
			CUSTOMER | GET /api/catalog/providers/{providerId}/closures
			CUSTOMER | GET /api/catalog/providers/{providerId}/deposit-terms
			CUSTOMER | GET /api/catalog/providers/{providerId}/services

			CUSTOMER | GET /api/customer/pets
			CUSTOMER | POST /api/customer/pets
			CUSTOMER | GET /api/customer/pets/{id}
			CUSTOMER | PUT /api/customer/pets/{id}
			CUSTOMER | POST /api/customer/pets/{id}/archive
			CUSTOMER | GET /api/customer/pets/{id}/history
			CUSTOMER | POST /api/customer/pets/{id}/reactivate

			ANY | GET /api/me
			CUSTOMER | POST /api/me/delete-account
			CUSTOMER | GET /api/me/export
			ANY | PUT /api/me/phone

			PROVIDER | GET /api/provider
			PROVIDER | POST /api/provider
			PROVIDER | PUT /api/provider
			PROVIDER | GET /api/provider/availability
			PROVIDER | PUT /api/provider/availability
			PROVIDER | POST /api/provider/customers/{customerId}/anonymize
			PROVIDER | GET /api/provider/customers/{customerId}/export
			PROVIDER | POST /api/provider/customers/{customerId}/reset-password
			PROVIDER | PUT /api/provider/deposit
			PROVIDER | GET /api/provider/public-page
			PROVIDER | PUT /api/provider/public-page
			PROVIDER | GET /api/provider/reports/lapsed
			PROVIDER | GET /api/provider/reports/summary
			PROVIDER | GET /api/provider/services
			PROVIDER | POST /api/provider/services
			PROVIDER | PUT /api/provider/services/{id}
			PROVIDER | GET /api/provider/staff
			PROVIDER | POST /api/provider/staff
			PROVIDER | POST /api/provider/staff/accounts
			PROVIDER | DELETE /api/provider/staff/{userId}
			PROVIDER | POST /api/provider/staff/{userId}/deactivate
			PROVIDER | GET /api/provider/time-off
			PROVIDER | POST /api/provider/time-off
			PROVIDER | DELETE /api/provider/time-off/{id}

			CUSTOMER,PROVIDER,ATTENDANT | GET /api/providers/{providerId}/slots
			PUBLIC | GET /api/public/studios/{slug}

			CUSTOMER | POST /api/waitlist
			CUSTOMER | GET /api/waitlist/mine
			CUSTOMER,PROVIDER,ATTENDANT | DELETE /api/waitlist/{id}
			PROVIDER,ATTENDANT | POST /api/waitlist/{id}/called
			""";

	private static final List<String> ROLES = List.of("ADMIN", "PROVIDER", "ATTENDANT", "CUSTOMER");
	private static final Path CONTRACT = Path.of("..", "docs", "openapi.json");
	private static final UUID SOME_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	/** "MÉTODO /caminho" -> perfis autorizados (vazio = PUBLIC). */
	private final Map<String, Set<String>> allowed = new LinkedHashMap<>();
	private final Map<String, Boolean> publicRoutes = new LinkedHashMap<>();
	private final Map<String, String> bearer = new LinkedHashMap<>();

	@BeforeEach
	void prepare() {
		allowed.clear();
		publicRoutes.clear();
		for (String line : TABLE.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			String[] parts = line.split("\\|");
			String roles = parts[0].trim();
			String operation = parts[1].trim();
			publicRoutes.put(operation, roles.equals("PUBLIC"));
			allowed.put(operation, roles.equals("PUBLIC") ? Set.of() : roles.equals("ANY") ? Set.copyOf(ROLES) : Set.of(roles.split(",")));
		}
		// Um usuário de cada perfil; dono e atendente ligados a um estúdio (sem isso o serviço recusa por falta de vínculo, o que não é falta de perfil).
		UUID admin = seedUser("m-admin@exemplo.test", "ADMIN", true);
		UUID owner = seedUser("m-owner@exemplo.test", "PROVIDER", true);
		UUID attendant = seedUser("m-att@exemplo.test", "ATTENDANT", true);
		UUID customer = seedUser("m-cust@exemplo.test", "CUSTOMER", true);
		UUID provider = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Estúdio da Matriz', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner);
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", provider, attendant);
		bearer.put("ADMIN", "Bearer " + validToken(admin));
		bearer.put("PROVIDER", "Bearer " + validToken(owner));
		bearer.put("ATTENDANT", "Bearer " + validToken(attendant));
		bearer.put("CUSTOMER", "Bearer " + validToken(customer));
	}

	private static Set<String> contractOperations() throws Exception {
		JsonNode paths = new ObjectMapper().readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8)).get("paths");
		Set<String> found = new TreeSet<>();
		paths.fieldNames().forEachRemaining(path -> paths.get(path).fieldNames().forEachRemaining(method -> found.add(method.toUpperCase() + " " + path)));
		return found;
	}

	@Test
	void todaRotaDoContratoDeclaraQuemPodeChamala() throws Exception {
		Set<String> contract = contractOperations();
		Set<String> declared = new TreeSet<>(allowed.keySet());
		Set<String> undeclared = new TreeSet<>(contract);
		undeclared.removeAll(declared);
		Set<String> obsolete = new TreeSet<>(declared);
		obsolete.removeAll(contract);
		assertTrue(undeclared.isEmpty(), "rota SEM linha na matriz de autorização (declare quem pode chamá-la em AuthorizationMatrixTest): " + undeclared);
		assertTrue(obsolete.isEmpty(), "linha da matriz de uma rota que não existe mais no contrato: " + obsolete);
		assertEquals(contract.size(), declared.size());
	}

	@Test
	void cadaPerfilSoAlcancaOQueADeclaracaoDiz() throws Exception {
		List<String> violations = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : allowed.entrySet()) {
			String operation = entry.getKey();
			boolean isPublic = publicRoutes.get(operation);

			int anonymous = call(operation, null);
			if (isPublic ? (anonymous == 401 || anonymous == 403) : anonymous != 401) {
				violations.add(operation + " como VISITANTE: " + anonymous + (isPublic ? " (rota pública não pode exigir login)" : " (esperado 401)"));
			}
			for (String role : ROLES) {
				int status = call(operation, bearer.get(role));
				boolean mayPass = isPublic || entry.getValue().contains(role);
				if (mayPass && (status == 401 || status == 403)) {
					violations.add(operation + " como " + role + ": " + status + " (deveria passar da autorização)");
				}
				if (!mayPass && status != 403) {
					violations.add(operation + " como " + role + ": " + status + " (esperado 403)");
				}
			}
		}
		assertTrue(violations.isEmpty(), "violações da matriz de autorização (" + violations.size() + "):\n" + String.join("\n", violations));
	}

	/** Chama a rota (com ids e corpo de mentira) e devolve o status HTTP. {@code authorization} nulo = visitante. */
	private int call(String operation, String authorization) throws Exception {
		requestLimiter.clearAll();
		loginLockoutLimiter.clearAll();
		String[] parts = operation.split(" ", 2);
		HttpMethod method = HttpMethod.valueOf(parts[0]);
		String path = parts[1].replace("{slug}", "estudio-inexistente").replaceAll("\\{[^}]+}", SOME_ID.toString());
		MockHttpServletRequestBuilder builder = request(method, path);
		if (method == HttpMethod.POST || method == HttpMethod.PUT) {
			builder.contentType(MediaType.APPLICATION_JSON).content("{}");
		}
		if (authorization != null) {
			builder.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return mvc.perform(builder).andReturn().getResponse().getStatus();
	}
}
