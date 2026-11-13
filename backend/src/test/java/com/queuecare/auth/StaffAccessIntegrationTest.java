package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.queuecare.scheduling.application.StaffAccess;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * StaffAccess: de qual prestador o usuário opera? Testado com o banco REAL e sem cache: cada mudança no banco vale na
 * chamada seguinte, sem emitir novo token.
 */
class StaffAccessIntegrationTest extends AuthIntegrationTestBase {

	@Autowired
	StaffAccess staffAccess;

	UUID ownerA;
	UUID providerA;
	UUID providerB;
	UUID attendant;

	@BeforeEach
	void seed() {
		ownerA = seedUser("dono-a@exemplo.test", "PROVIDER", true);
		providerA = insertProvider(ownerA, "Negócio A");
		providerB = insertProvider(seedUser("dono-b@exemplo.test", "PROVIDER", true), "Negócio B");
		attendant = seedUser("atendente@exemplo.test", "ATTENDANT", true);
	}

	private UUID insertProvider(UUID userId, String name) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone) VALUES (?, ?, 'America/Sao_Paulo') RETURNING id", UUID.class, userId, name);
	}

	private void link(UUID provider, UUID user) {
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", provider, user);
	}

	@Test
	void prestadorOperaNoProprioNegocio() {
		assertEquals(Optional.of(providerA), staffAccess.providerIdOf(ownerA));
	}

	@Test
	void prestadorSemNegocioCadastradoNaoOperaEmNenhum() {
		UUID semNegocio = seedUser("sem-negocio@exemplo.test", "PROVIDER", true);

		assertEquals(Optional.empty(), staffAccess.providerIdOf(semNegocio));
	}

	@Test
	void atendenteVinculadoOperaNoPrestadorDoVinculo() {
		link(providerB, attendant);

		assertEquals(Optional.of(providerB), staffAccess.providerIdOf(attendant));
	}

	@Test
	void atendenteSemVinculoNaoOperaEmNenhumPrestador() {
		assertEquals(Optional.empty(), staffAccess.providerIdOf(attendant));
	}

	@Test
	void clienteNuncaOperaComoEquipe() {
		UUID customer = seedUser("cliente@exemplo.test", "CUSTOMER", true);

		assertEquals(Optional.empty(), staffAccess.providerIdOf(customer));
	}

	@Test
	void usuarioInativoOuInexistenteNaoOpera() {
		link(providerA, attendant);
		jdbc.update("UPDATE users SET active = false WHERE id = ?", attendant);
		jdbc.update("UPDATE users SET active = false WHERE id = ?", ownerA);

		assertEquals(Optional.empty(), staffAccess.providerIdOf(attendant));
		assertEquals(Optional.empty(), staffAccess.providerIdOf(ownerA));
		assertEquals(Optional.empty(), staffAccess.providerIdOf(UUID.randomUUID()));
	}

	@Test
	void mudancasNoBancoValemNaChamadaSeguinteSemCache() {
		assertEquals(Optional.empty(), staffAccess.providerIdOf(attendant));

		link(providerA, attendant);
		assertEquals(Optional.of(providerA), staffAccess.providerIdOf(attendant));

		jdbc.update("DELETE FROM provider_staff WHERE user_id = ?", attendant);
		assertEquals(Optional.empty(), staffAccess.providerIdOf(attendant), "desvincular vale imediatamente");

		link(providerB, attendant);
		assertEquals(Optional.of(providerB), staffAccess.providerIdOf(attendant), "trocar de prestador não preserva o contexto antigo");
	}

	@Test
	void umaMesmaPessoaNaoMisturaPrestadores() {
		link(providerB, attendant);

		assertEquals(Optional.of(providerA), staffAccess.providerIdOf(ownerA), "o dono do A continua só no A");
		assertEquals(Optional.of(providerB), staffAccess.providerIdOf(attendant), "o atendente do B nunca resolve para o A");
	}
}
