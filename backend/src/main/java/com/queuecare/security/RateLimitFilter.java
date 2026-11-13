package com.queuecare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de requisições por minuto nos endpoints mais sensíveis a abuso: login e cadastro (por endereço) e consulta de horários
 * livres (por usuário autenticado; sem login, por endereço). Excedeu: 429 com {@code Retry-After} e corpo genérico, sem detalhes.
 * Roda DEPOIS da leitura do token, então já conhece o usuário. O endereço é o da conexão ({@code getRemoteAddr}); atrás de proxy,
 * configure {@code server.forward-headers-strategy} para que seja o do cliente real.
 */
final class RateLimitFilter extends OncePerRequestFilter {

	private static final Duration WINDOW = Duration.ofMinutes(1);

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
		String path = request.getRequestURI();
		String method = request.getMethod();
		if ("POST".equals(method) && "/api/auth/login".equals(path)) {
			key = "login|" + request.getRemoteAddr();
			limit = properties.loginPerMinute();
		} else if ("POST".equals(method) && "/api/auth/register".equals(path)) {
			key = "register|" + request.getRemoteAddr();
			limit = properties.registerPerMinute();
		} else if ("GET".equals(method) && path.startsWith("/api/providers/") && path.endsWith("/slots")) {
			key = "slots|" + principalOrAddress(request);
			limit = properties.slotsPerMinute();
		}
		if (key != null && !limiter.tryAcquire(key, limit, WINDOW)) {
			tooManyRequests(response, limiter.retryAfterSeconds(key, WINDOW));
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
