package com.queuecare.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.queuecare.pets.PetExceptions.InvalidPetException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Normalização e validação do texto do pet (ADR 0016, item 1): a aplicação transforma, o banco confere (PetConstraintsTest). */
class PetTextTest {

	private static PetText.Normalized ok(String name, String species, String breed, String size, String notes) {
		return PetText.normalize(new PetText.Input(name, species, breed, size, notes));
	}

	private static Map<String, String> errorsOf(String name, String species, String breed, String size, String notes) {
		return assertThrows(InvalidPetException.class, () -> ok(name, species, breed, size, notes)).errors();
	}

	@Test
	void nomeEhAparadoEEspacosInternosRepetidosViramUm() {
		assertEquals("Rex Jr", ok("  Rex    Jr  ", "DOG", null, null, null).name());
		assertEquals("Rex", ok("\tRex\n", "DOG", null, null, null).name(), "espaços e quebras nas PONTAS são aparados, não recusados");
	}

	@Test
	void racaEPorteVaziosViramNuloENomeVazioEhErro() {
		PetText.Normalized n = ok("Rex", "DOG", "   ", "  ", null);
		assertNull(n.breed());
		assertNull(n.size());
		assertEquals(Map.of("name", "obrigatório, até 80 caracteres"), errorsOf("   ", "DOG", null, null, null));
		assertEquals(Map.of("name", "obrigatório, até 80 caracteres"), errorsOf(null, "DOG", null, null, null));
	}

	@Test
	void especieEObrigatoriaEExata() {
		assertEquals(PetSpecies.CAT, ok("Mel", "CAT", null, null, null).species());
		for (String bad : new String[] { null, "", " ", "dog", "Dog", "BIRD", "DOG " }) {
			assertEquals(Map.of("species", "obrigatório: DOG ou CAT"), errorsOf("Rex", bad, null, null, null), String.valueOf(bad));
		}
	}

	@Test
	void porteAceitaSoALista() {
		assertEquals(PetSize.LARGE, ok("Rex", "DOG", null, "LARGE", null).size());
		assertEquals(PetSize.LARGE, ok("Rex", "DOG", null, " LARGE ", null).size(), "espaços nas pontas do porte são aparados");
		assertEquals(Map.of("size", "SMALL, MEDIUM ou LARGE"), errorsOf("Rex", "DOG", null, "HUGE", null));
		assertEquals(Map.of("size", "SMALL, MEDIUM ou LARGE"), errorsOf("Rex", "DOG", null, "small", null));
	}

	@Test
	void controlesNoNomeERacaSaoRecusadosMasNasPontasSaoAparados() {
		for (String bad : new String[] { "Re\tx", "Re\nx", "Re\rx", "Re\u0007x", "Re\u007Fx", "Re\u0085x", "Re\u0000x" }) {
			assertEquals(Map.of("name", "caracteres inválidos"), errorsOf(bad, "DOG", null, null, null));
			assertEquals(Map.of("breed", "caracteres inválidos"), errorsOf("Rex", "DOG", bad, null, null));
		}
	}

	@Test
	void controlesNasPontasQueSaoEspacoSaoAparadosOsDemaisSaoRecusados() {
		// aparados: TAB, LF, VT, FF, CR nas pontas (colar com quebra de linha no fim é normal)
		assertEquals("Rex", ok("\t\n\u000B\f\rRex\r\f\u000B\n\t", "DOG", null, null, null).name());
		assertEquals("Poodle", ok("Rex", "DOG", "\tPoodle\n", null, null).breed());
		assertEquals("anotação", ok("Rex", "DOG", null, null, "\t\nanotação\u000B\f").notes());
		// recusados mesmo nas pontas: controles que não são espaço
		for (String bad : new String[] { "\u0007Rex", "Rex\u0007", "\u0000Rex", "Rex\u007F", "\u0085Rex", "Rex\u009F" }) {
			assertEquals(Map.of("name", "caracteres inválidos"), errorsOf(bad, "DOG", null, null, null), "name " + bad.length());
			assertEquals(Map.of("breed", "caracteres inválidos"), errorsOf("Rex", "DOG", bad, null, null), "breed " + bad.length());
			assertEquals(Map.of("notes", "caracteres inválidos"), errorsOf("Rex", "DOG", null, null, bad), "notes " + bad.length());
		}
	}

	@Test
	void tamanhoContaPontosDeCodigoNaoUnidadesUtf16() {
		assertEquals(80, ok("🐶".repeat(80), "DOG", null, null, null).name().codePointCount(0, 160));
		assertEquals(Map.of("name", "até 80 caracteres"), errorsOf("🐶".repeat(81), "DOG", null, null, null));
		assertEquals("x".repeat(80), ok("x".repeat(80), "DOG", null, null, null).name());
		assertEquals(Map.of("name", "até 80 caracteres"), errorsOf("x".repeat(81), "DOG", null, null, null));
		assertEquals(Map.of("breed", "até 80 caracteres"), errorsOf("Rex", "DOG", "x".repeat(81), null, null));
	}

	@Test
	void observacoesConvertemQuebrasAparamEVaziamParaNulo() {
		assertEquals("a\nb", ok("Rex", "DOG", null, null, "  a\r\nb\r\n").notes());
		assertEquals("a\nb\nc", ok("Rex", "DOG", null, null, "a\rb\nc").notes());
		assertEquals("a\n\nb", ok("Rex", "DOG", null, null, "a\n\nb").notes(), "linhas em branco internas são preservadas");
		assertNull(ok("Rex", "DOG", null, null, " \r\n \n ").notes());
		assertNull(ok("Rex", "DOG", null, null, "").notes());
		assertNull(ok("Rex", "DOG", null, null, null).notes());
	}

	@Test
	void observacoesRecusamControlesExcetoQuebraDeLinha() {
		for (String bad : new String[] { "a\tb", "a\u0007b", "a\u007Fb", "a\u0085b", "a\u0000b" }) {
			assertEquals(Map.of("notes", "caracteres inválidos"), errorsOf("Rex", "DOG", null, null, bad));
		}
	}

	@Test
	void observacoesLimitadasA500PontosDeCodigoDepoisDeNormalizar() {
		assertEquals(500, ok("Rex", "DOG", null, null, "x".repeat(500)).notes().length());
		assertEquals(Map.of("notes", "até 500 caracteres"), errorsOf("Rex", "DOG", null, null, "x".repeat(501)));
		// CRLF conta como UMA quebra, pois o tamanho é medido DEPOIS de normalizar: 249 + CRLF (2 unidades) + 250 = 501 antes, 500 depois.
		assertEquals(500, ok("Rex", "DOG", null, null, "x".repeat(249) + "\r\n" + "y".repeat(250)).notes().length());
		assertEquals(Map.of("notes", "até 500 caracteres"), errorsOf("Rex", "DOG", null, null, "x".repeat(250) + "\r\n" + "y".repeat(250)));
		assertEquals(500, ok("Rex", "DOG", null, null, "🐶".repeat(500)).notes().codePointCount(0, 1000));
	}

	@Test
	void todosOsErrosVemJuntosENuncaRepetemOValorEnviado() {
		Map<String, String> errors = errorsOf("", "BIRD", "x".repeat(81), "HUGE", "segredo\tdo tutor");
		assertEquals(5, errors.size());
		String all = errors.toString();
		assertFalse(all.contains("segredo"), all);
		assertFalse(all.contains("BIRD"), all);
		assertFalse(all.contains("HUGE"), all);
		assertTrue(errors.containsKey("notes"));
	}
}
