package com.queuecare.security;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * FONTE ÚNICA das rotas que podem responder 429. O {@link RateLimitFilter} monta os seus casamentos A PARTIR desta lista e o gerador do
 * contrato OpenAPI documenta o 429 SÓ nestas rotas: se uma rota entra ou sai do limite, o contrato muda junto (e o teste do contrato
 * quebra até o arquivo ser atualizado).
 */
public final class RateLimitedRoutes {

	/** Uma rota limitada: método e padrão de caminho (o mesmo formato das regras de autorização). */
	public record Route(HttpMethod method, String pattern) {
	}

	public static final Route LOGIN = new Route(HttpMethod.POST, "/api/auth/login");
	public static final Route REGISTER = new Route(HttpMethod.POST, "/api/auth/register");
	public static final Route CREATE_PROVIDER = new Route(HttpMethod.POST, "/api/admin/providers");
	public static final Route CREATE_ATTENDANT = new Route(HttpMethod.POST, "/api/provider/staff/accounts");
	public static final Route CATALOG = new Route(HttpMethod.GET, "/api/catalog/**");
	public static final Route SLOTS = new Route(HttpMethod.GET, "/api/providers/*/slots");

	/** Limitadas por minuto ou por hora pelo {@link RateLimitFilter}. */
	static final List<Route> FILTERED = List.of(LOGIN, REGISTER, CREATE_PROVIDER, CREATE_ATTENDANT, CATALOG, SLOTS);

	/** Responde 429 por OUTRO mecanismo: o limite de tentativas de troca de senha (PasswordService); o login também tem o bloqueio por tentativas. */
	static final Route CHANGE_PASSWORD = new Route(HttpMethod.POST, "/api/auth/change-password");

	private static final List<Route> ALL = List.of(LOGIN, REGISTER, CREATE_PROVIDER, CREATE_ATTENDANT, CATALOG, SLOTS, CHANGE_PASSWORD);

	private RateLimitedRoutes() {
	}

	/** Esta operação (método HTTP e caminho no formato OpenAPI, com {@code {variáveis}}) pode responder 429? */
	public static boolean covers(String method, String openApiPath) {
		String sample = openApiPath.replaceAll("\\{[^}]+}", "x");
		return ALL.stream().anyMatch(route -> route.method().name().equalsIgnoreCase(method)
				&& new PathPatternParser().parse(route.pattern()).matches(PathContainer.parsePath(sample)));
	}
}
