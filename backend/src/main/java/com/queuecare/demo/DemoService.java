package com.queuecare.demo;

import com.queuecare.auth.AuthService;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demonstração pública (ADR 0041): quem visita o site entra com um clique como dono, atendente ou tutora de um estúdio FICTÍCIO, sem cadastro e sem senha.
 * Só existe quando {@code queuecare.demo.enabled=true} (DEMO_ENABLED no Render); desligada, nada disto é carregado e a rota nem existe.
 *
 * <p>Os dados fictícios são repostos <b>na hora do clique, se passou o prazo</b> ({@code queuecare.demo.reset-after}, 60 min): não depende de agendador, que
 * no plano gratuito não roda (ADR 0037). Quem entra dentro do prazo vê o que a pessoa anterior deixou, e é isso que o aviso na tela explica.
 */
@Service
@ConditionalOnProperty(name = "queuecare.demo.enabled", havingValue = "true")
public class DemoService {

	/** Trava de consultoria do PostgreSQL: duas pessoas clicando ao mesmo tempo repõem uma vez só. */
	private static final long RESET_LOCK = 7_204_117L;

	private final JdbcTemplate jdbc;
	private final UserAccountRepository users;
	private final AuthService auth;
	private final DemoSeed seed;
	private final Clock clock;
	private final Duration resetAfter;

	DemoService(JdbcTemplate jdbc, UserAccountRepository users, AuthService auth, DemoSeed seed, Clock clock,
			@Value("${queuecare.demo.reset-after:60m}") Duration resetAfter) {
		this.jdbc = jdbc;
		this.users = users;
		this.auth = auth;
		this.seed = seed;
		this.clock = clock;
		this.resetAfter = resetAfter;
	}

	/** O perfil da demonstração a que cada botão da tela corresponde. */
	public enum Profile {
		OWNER(DemoSeed.OWNER_EMAIL), ATTENDANT(DemoSeed.ATTENDANT_EMAIL), CUSTOMER(DemoSeed.CUSTOMER_EMAIL);

		final String email;

		Profile(String email) {
			this.email = email;
		}
	}

	@Transactional
	public AuthService.IssuedToken enter(Profile profile) {
		resetIfStale();
		UserAccount user = users.findByEmail(profile.email).orElseThrow();
		return auth.issueToken(user);
	}

	private void resetIfStale() {
		// Primeiro a trava, depois a conferência: quem esperou a trava encontra a reposição já feita pela outra pessoa.
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, RESET_LOCK);
		List<Timestamp> last = jdbc.query("SELECT reset_at FROM demo_state WHERE id = 1", (rs, i) -> rs.getTimestamp(1));
		Instant now = clock.instant();
		if (!last.isEmpty() && last.get(0).toInstant().plus(resetAfter).isAfter(now)) {
			return;
		}
		seed.reset();
		jdbc.update("INSERT INTO demo_state (id, reset_at) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET reset_at = EXCLUDED.reset_at", Timestamp.from(now));
	}

}
