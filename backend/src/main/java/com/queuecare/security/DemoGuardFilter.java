package com.queuecare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Só existe com a demonstração pública ligada (ADR 0041). As contas da demonstração são COMPARTILHADAS por todos os visitantes e ficam no banco de produção, então
 * quatro operações que mexeriam na conta em si (e não nos dados do estúdio fictício) respondem 403 {@code DEMO_READ_ONLY}: trocar a senha, excluir a conta,
 * cadastrar atendentes (a conta nova ficaria para sempre na auditoria, que é só de acréscimo) e gerar senha temporária para um tutor.
 * Contas reais passam direto. A conta é reconhecida pelo e-mail no domínio reservado {@code .invalid}, que o cadastro público nunca cria.
 */
final class DemoGuardFilter extends OncePerRequestFilter {

	private static final String DOMAIN = "@demo.invalid";

	private static final RequestMatcher BLOCKED = new OrRequestMatcher(
			matcher("/api/auth/change-password"),
			matcher("/api/me/delete-account"),
			matcher("/api/provider/staff/accounts"),
			matcher("/api/provider/customers/*/reset-password"));

	private static RequestMatcher matcher(String pattern) {
		return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern);
	}

	private final JdbcTemplate jdbc;

	DemoGuardFilter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		if (BLOCKED.matches(request) && isDemoAccount(SecurityContextHolder.getContext().getAuthentication())) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType("application/problem+json");
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"operação indisponível na demonstração\",\"reason\":\"DEMO_READ_ONLY\"}");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean isDemoAccount(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
			return false;
		}
		try {
			UUID id = UUID.fromString(authentication.getName());
			Boolean demo = jdbc.queryForObject("SELECT email LIKE '%" + DOMAIN + "' FROM users WHERE id = ?", Boolean.class, id);
			return Boolean.TRUE.equals(demo);
		} catch (IllegalArgumentException | org.springframework.dao.DataAccessException e) {
			return false;
		}
	}
}
