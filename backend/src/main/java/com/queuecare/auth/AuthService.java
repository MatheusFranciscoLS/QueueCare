package com.queuecare.auth;

import com.queuecare.auth.AuthExceptions.InvalidCredentialsException;
import com.queuecare.auth.AuthExceptions.InvalidPasswordException;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import com.queuecare.auth.AuthExceptions.TooManyLoginAttemptsException;
import com.queuecare.security.JwtProperties;
import com.queuecare.security.RateLimitProperties;
import com.queuecare.security.RateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro e login (ADR 0003). Decisões de segurança:
 * <ul>
 *   <li>o cadastro público SEMPRE cria CUSTOMER (o papel nunca vem do cliente);</li>
 *   <li>e-mail normalizado (sem espaços externos, minúsculas); a garantia final contra duplicidade é o índice único do banco;</li>
 *   <li>senha: mínimo de 10 caracteres e máximo de 72 bytes em UTF-8 (limite do BCrypt; nunca truncada em silêncio);</li>
 *   <li>login com mensagem única "credenciais inválidas" e BCrypt calculado SEMPRE (contra um hash fictício pré-calculado
 *       quando o usuário não existe ou está inativo), para não revelar contas pelo tempo de resposta.</li>
 * </ul>
 */
@Service
public class AuthService {

	static final int MIN_PASSWORD_CHARACTERS = 10;
	static final int MAX_PASSWORD_BYTES = 72;

	private final UserAccountRepository users;
	private final AccountProvisioner provisioner;
	private final PasswordEncoder passwordEncoder;
	private final JwtEncoder jwtEncoder;
	private final JwtProperties jwtProperties;
	private final Clock clock;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties rateLimitProperties;
	/** Hash BCrypt fictício calculado uma única vez, para igualar o tempo de login de contas inexistentes. */
	private final String dummyHash;

	public AuthService(UserAccountRepository users, AccountProvisioner provisioner, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
			JwtProperties jwtProperties, Clock clock, @Qualifier(RateLimiter.LOGIN_LOCKOUT_LIMITER) RateLimiter rateLimiter,
			RateLimitProperties rateLimitProperties) {
		this.users = users;
		this.provisioner = provisioner;
		this.passwordEncoder = passwordEncoder;
		this.jwtEncoder = jwtEncoder;
		this.jwtProperties = jwtProperties;
		this.clock = clock;
		this.rateLimiter = rateLimiter;
		this.rateLimitProperties = rateLimitProperties;
		this.dummyHash = passwordEncoder.encode("senha-ficticia-so-para-igualar-o-tempo");
	}

	/** Resultado do login: o token e a validade em segundos. */
	public record IssuedToken(String accessToken, long expiresInSeconds, boolean mustChangePassword) {
	}

	public static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	/** O cadastro público SEMPRE cria CUSTOMER, sem troca de senha obrigatória (a senha é da própria pessoa). */
	@Transactional
	public UserAccount registerCustomer(String name, String email, String rawPassword) {
		return provisioner.create(UserRole.CUSTOMER, name, email, rawPassword, false);
	}

	/**
	 * Login. Além das regras abaixo, aplica um BLOQUEIO TEMPORÁRIO por tentativas: o par e-mail + endereço tem direito a N
	 * tentativas SEM sucesso dentro da janela; a seguinte responde 429 (com Retry-After) antes mesmo de comparar a senha.
	 * A tentativa é RESERVADA antes de conferir a senha, numa operação atômica do limitador: com muitas requisições
	 * simultâneas, só N chegam a ser conferidas (conferir e depois registrar deixaria passar tentativas em paralelo).
	 * TODA tentativa conta (e-mail inexistente, inativo, senha errada ou grande demais), então o bloqueio não revela quais
	 * contas existem; e a chave inclui o endereço, então quem erra não consegue trancar a conta para o dono em outro endereço.
	 * Login com sucesso zera a contagem.
	 */
	@Transactional(readOnly = true)
	public IssuedToken login(String email, String rawPassword, String clientAddress) {
		String normalizedEmail = normalizeEmail(email);
		if (!rateLimitProperties.loginLockoutEnabled()) {
			return authenticate(normalizedEmail, rawPassword);
		}
		String attemptKey = "loginfail|" + normalizedEmail + "|" + clientAddress;
		Duration window = Duration.ofMinutes(rateLimitProperties.loginFailureWindowMinutes());
		if (!rateLimiter.tryAcquire(attemptKey, rateLimitProperties.loginFailuresPerEmailAndIp(), window)) {
			throw new TooManyLoginAttemptsException(rateLimiter.retryAfterSeconds(attemptKey, window));
		}
		IssuedToken token = authenticate(normalizedEmail, rawPassword);
		rateLimiter.clear(attemptKey);
		return token;
	}

	private IssuedToken authenticate(String normalizedEmail, String rawPassword) {
		Optional<UserAccount> user = users.findByEmail(normalizedEmail).filter(UserAccount::isActive);
		boolean fitsBcrypt = rawPassword.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
		// Sempre compara com algum hash, para o tempo de resposta não revelar se a conta existe.
		String hashToCheck = user.map(UserAccount::getPasswordHash).orElse(dummyHash);
		String candidate = fitsBcrypt ? rawPassword : "x";
		boolean matches = passwordEncoder.matches(candidate, hashToCheck);
		if (user.isEmpty() || !fitsBcrypt || !matches) {
			throw new InvalidCredentialsException();
		}
		return issueToken(user.get());
	}

	private IssuedToken issueToken(UserAccount user) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(jwtProperties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(jwtProperties.issuer())
				.audience(List.of(jwtProperties.audience()))
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim("role", user.getRole().name())
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, jwtProperties.accessTokenTtl().toSeconds(), user.isMustChangePassword());
	}

	static void validatePassword(String rawPassword) {
		if (rawPassword == null || rawPassword.codePointCount(0, rawPassword.length()) < MIN_PASSWORD_CHARACTERS) {
			throw new InvalidPasswordException("a senha deve ter ao menos " + MIN_PASSWORD_CHARACTERS + " caracteres");
		}
		if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new InvalidPasswordException("a senha deve ter no máximo " + MAX_PASSWORD_BYTES + " bytes em UTF-8");
		}
	}
}
