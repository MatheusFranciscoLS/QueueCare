package com.queuecare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de requisições por minuto nos endpoints mais sensíveis a abuso: login e cadastro (por endereço) e consulta de horários
 * livres (por usuário autenticado; sem login, por endereço). Excedeu: 429 com {@code Retry-After} e corpo genérico, sem detalhes.
 * Roda DEPOIS da leitura do token, então já conhece o usuário. O endereço é o da conexão ({@code getRemoteAddr}); atrás de proxy,
 * configure {@code server.forward-headers-strategy} para que seja o do cliente real (ADR 0003: só com proxy controlado, que
 * SUBSTITUI os cabeçalhos Forwarded/X-Forwarded-For enviados pelo cliente).
 *
 * <p>As rotas são reconhecidas com o MESMO tipo de casamento de caminho que as regras de autorização usam
 * ({@link PathPatternRequestMatcher}: caminho já normalizado, sem parâmetros de caminho como {@code ;x=1}); não se compara
 * a string bruta da URL. Variantes que o Spring não roteia (barra final, maiúsculas, {@code //}, {@code ;}) não chegam ao
 * controller e, portanto, não precisam ser contadas; os testes de integração provam isso.
 */
final class RateLimitFilter extends OncePerRequestFilter {

	private static final Duration WINDOW = Duration.ofMinutes(1);

	private static final RequestMatcher LOGIN = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login");
	private static final RequestMatcher REGISTER = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/register");
	private static final Duration HOUR = Duration.ofHours(1);
	private static final RequestMatcher CREATE_PROVIDER = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/admin/providers");
	private static final RequestMatcher CREATE_ATTENDANT = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/provider/staff/accounts");
	private static final RequestMatcher SLOTS = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/providers/*/slots");

	private final RateLimiter limiter;
	private final RateLimitProperties properties;

	RateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String key = null;
		int limit = 0;
		Duration window = WINDOW;
		if (LOGIN.matches(request)) {
			key = "login|" + request.getRemoteAddr();
			limit = properties.loginPerMinute();
		} else if (REGISTER.matches(request)) {
			key = "register|" + request.getRemoteAddr();
			limit = properties.registerPerMinute();
		} else if (CREATE_PROVIDER.matches(request) || CREATE_ATTENDANT.matches(request)) {
			key = "accounts|" + principalOrAddress(request);
			limit = properties.accountCreationsPerHour();
			window = HOUR;
		} else if (SLOTS.matches(request)) {
			key = "slots|" + principalOrAddress(request);
			limit = properties.slotsPerMinute();
		}
		if (key != null && !limiter.tryAcquire(key, limit, window)) {
			tooManyRequests(response, limiter.retryAfterSeconds(key, window));
			return;
		}
		chain.doFilter(request, response);
	}

	private static String principalOrAddress(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
			return "u:" + authentication.getName();
		}
		return "ip:" + request.getRemoteAddr();
	}

	static void tooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		response.setContentType("application/problem+json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"muitas requisições; tente novamente mais tarde\"}");
	}
}
