package com.queuecare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Conta com troca de senha pendente ({@code users.must_change_password}, lida do BANCO a cada requisição pelo conversor do
 * token): só pode consultar {@code GET /api/me} e trocar a senha ({@code POST /api/auth/change-password}); QUALQUER outra
 * rota autenticada responde 403 com o código {@code PASSWORD_CHANGE_REQUIRED}. Fica depois da leitura do token e antes do
 * limite de requisições e da autorização das rotas. O caminho é casado como nas regras de autorização (normalizado).
 */
final class PasswordChangeRequiredFilter extends OncePerRequestFilter {

	static final String AUTHORITY = "PASSWORD_CHANGE_REQUIRED";

	private static final RequestMatcher ME = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/me");
	private static final RequestMatcher CHANGE_PASSWORD = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/change-password");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean restricted = authentication != null && authentication.getAuthorities().stream().anyMatch(a -> AUTHORITY.equals(a.getAuthority()));
		if (restricted && !ME.matches(request) && !CHANGE_PASSWORD.matches(request)) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType("application/problem+json");
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"é preciso trocar a senha antes de continuar\",\"reason\":\"PASSWORD_CHANGE_REQUIRED\"}");
			return;
		}
		chain.doFilter(request, response);
	}
}
