package com.queuecare.auth;

import com.queuecare.auth.AuthExceptions.EmailAlreadyRegisteredException;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.sql.SQLException;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ÚNICO ponto que cria usuários (cadastro público, bootstrap do ADMIN, prestador criado pelo ADMIN e atendente criado pelo
 * prestador). Garante, em todos os caminhos: senha dentro da política (e nunca registrada em log), e-mail normalizado e
 * único (a palavra final é o índice único do banco) e papel DEFINIDO PELO CÓDIGO que chama, nunca por dados de requisição.
 */
@Service
public class AccountProvisioner {

	private static final String UNIQUE_VIOLATION = "23505";

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public AccountProvisioner(UserAccountRepository users, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/** Cadastro público: e-mail repetido é {@code EmailAlreadyRegisteredException} (409 "e-mail já cadastrado"). */
	@Transactional
	UserAccount registerPublic(UserRole role, String name, String email, String rawPassword) {
		return create(role, name, email, rawPassword, false);
	}

	/**
	 * Criação de conta POR OUTRA PESSOA (ADMIN, prestador): e-mail repetido é {@link AccountConflictException}, uma resposta
	 * UNIFORME (409 com o mesmo corpo para qualquer perfil da conta existente) que não confirma nem descreve a conta.
	 * Participa da transação do chamador.
	 */
	@Transactional
	public UserAccount provision(UserRole role, String name, String email, String rawPassword, boolean mustChangePassword) {
		try {
			return create(role, name, email, rawPassword, mustChangePassword);
		} catch (EmailAlreadyRegisteredException e) {
			throw new AccountConflictException();
		}
	}

	private UserAccount create(UserRole role, String name, String email, String rawPassword, boolean mustChangePassword) {
		AuthService.validatePassword(rawPassword);
		String normalizedEmail = AuthService.normalizeEmail(email);
		if (users.findByEmail(normalizedEmail).isPresent()) {
			throw new EmailAlreadyRegisteredException();
		}
		UserAccount user = new UserAccount(name.trim(), normalizedEmail, passwordEncoder.encode(rawPassword), role, clock.instant(), mustChangePassword);
		try {
			return users.saveAndFlush(user);
		} catch (DataIntegrityViolationException e) {
			// Duas requisições com o mesmo e-mail ao mesmo tempo: o índice único do banco decide.
			if (hasSqlState(e, UNIQUE_VIOLATION)) {
				throw new EmailAlreadyRegisteredException();
			}
			throw e;
		}
	}

	private static boolean hasSqlState(Throwable error, String sqlState) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}
}
