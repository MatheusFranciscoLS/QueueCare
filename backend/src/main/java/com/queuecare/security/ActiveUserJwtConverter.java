package com.queuecare.security;

import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Transforma um JWT JÁ validado (assinatura, emissor, audiência, expiração) em autenticação, consultando o usuário no
 * banco: o token prova a identidade, mas o perfil (role) vem do banco e usuário inexistente ou inativo é recusado (401).
 * Assim, desativar um usuário vale na hora, sem esperar o token expirar (ADR 0003).
 */
@Component
class ActiveUserJwtConverter implements Converter<Jwt, AbstractOAuth2TokenAuthenticationToken<Jwt>> {

	private final UserAccountRepository users;

	ActiveUserJwtConverter(UserAccountRepository users) {
		this.users = users;
	}

	@Override
	public AbstractOAuth2TokenAuthenticationToken<Jwt> convert(Jwt jwt) {
		UUID userId = UUID.fromString(jwt.getSubject());
		UserAccount user = users.findById(userId)
				.filter(UserAccount::isActive)
				.orElseThrow(() -> new InvalidBearerTokenException("usuário inválido ou inativo"));
		List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
		return new JwtAuthenticationToken(jwt, authorities, user.getId().toString());
	}
}
