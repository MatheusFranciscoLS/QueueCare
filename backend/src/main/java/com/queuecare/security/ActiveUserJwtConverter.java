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
		// Troca ou redefinição de senha invalida TODO token anterior: a versão do token tem de ser a atual do banco (ausente = 0).
		if (tokenVersionOf(jwt) != user.getTokenVersion()) {
			throw new InvalidBearerTokenException("token emitido antes da última troca de senha");
		}
		// Conta com senha inicial: SÓ a autoridade de troca de senha, nenhum perfil. Assim nenhuma rota por perfil abre, e o
		// PasswordChangeRequiredFilter fecha também as rotas que exigem apenas estar autenticado.
		List<GrantedAuthority> authorities = user.isMustChangePassword()
				? List.of(new SimpleGrantedAuthority(PasswordChangeRequiredFilter.AUTHORITY))
				: List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
		return new JwtAuthenticationToken(jwt, authorities, user.getId().toString());
	}

	/**
	 * Versão das credenciais gravada no token. Claim AUSENTE vale 0 (tokens anteriores a este recurso). Claim PRESENTE precisa ser
	 * um número inteiro dentro da faixa de int: null, texto, decimal (0.5), booleano ou número fora da faixa NUNCA viram 0; viram
	 * um valor impossível (-1), que não confere com nenhuma versão do banco.
	 */
	static long tokenVersionOf(Jwt jwt) {
		if (!jwt.getClaims().containsKey("ver")) {
			return 0;
		}
		Object claim = jwt.getClaims().get("ver");
		if (claim instanceof Integer i) {
			return i;
		}
		if (claim instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
			return l;
		}
		return -1;
	}
}
