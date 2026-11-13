package com.queuecare.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Extrai o token Bearer do cabeçalho Authorization, mas RECUSA a requisição (401) se houver mais de um cabeçalho
 * Authorization: nunca escolher um deles em silêncio. Token na query string continua não sendo aceito (padrão do Spring).
 */
final class SingleAuthorizationHeaderResolver implements BearerTokenResolver {

	private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

	@Override
	public String resolve(HttpServletRequest request) {
		if (Collections.list(request.getHeaders("Authorization")).size() > 1) {
			throw new InvalidBearerTokenException("mais de um cabeçalho Authorization");
		}
		return delegate.resolve(request);
	}
}
