package com.queuecare.pets;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalização e validação dos dados do pet (ADR 0016, item 1). A APLICAÇÃO transforma a entrada; o BANCO recusa o que não está
 * na forma normalizada (V8). As duas camadas usam as mesmas regras:
 * <ul>
 * <li>{@code name}: obrigatório; apara as pontas; colapsa espaços internos repetidos; 1 a 80 caracteres; sem caracteres de controle.</li>
 * <li>{@code species}: obrigatório, exatamente DOG ou CAT.</li>
 * <li>{@code breed}: opcional; mesmas regras do nome; vazio vira nulo.</li>
 * <li>{@code size}: opcional (SMALL, MEDIUM, LARGE); vazio vira nulo.</li>
 * <li>{@code notes}: opcional, até 500 caracteres; CRLF e CR viram LF; apara as pontas; só o LF é aceito entre os caracteres
 * de controle; vazio vira nulo.</li>
 * <li>Ficha (ADR 0019, todos opcionais): {@code coat} (SHORT, MEDIUM, LONG, CURLY, DOUBLE), {@code weightKg} (0,5 a 120,0 com no máximo
 * uma casa decimal), {@code temperament} (CALM, ANXIOUS, REACTIVE), {@code healthNotes} (até 300 caracteres, mesmas regras de {@code notes})
 * e {@code vaccinated} (sim, não ou não informado).</li>
 * </ul>
 * <p><b>Controles nas pontas:</b> o que {@code String.strip()} considera espaço (TAB, LF, VT, FF, CR, U+001C a U+001F e espaços Unicode) é
 * APARADO, pois um tutor que cola o texto com uma quebra de linha no fim não deve receber erro; qualquer outro controle (sino, NUL, DEL,
 * C1 como U+0085) é RECUSADO em qualquer posição, inclusive nas pontas. No meio do nome e da raça todo controle é recusado; em {@code notes}
 * só o LF.
 *
 * <p>Tamanho é contado em pontos de código (como o {@code char_length} do PostgreSQL), não em unidades UTF-16. As mensagens de erro
 * nunca repetem o valor recebido (as observações são dado pessoal).
 */
public final class PetText {

	static final int MAX_NAME = 80;
	static final int MAX_BREED = 80;
	static final int MAX_NOTES = 500;
	static final int MAX_HEALTH_NOTES = 300;
	static final BigDecimal MIN_WEIGHT = new BigDecimal("0.5");
	static final BigDecimal MAX_WEIGHT = new BigDecimal("120.0");

	private PetText() {
	}

	/** Entrada crua (como veio da API). */
	public record Input(String name, String species, String breed, String size, String notes, String coat, BigDecimal weightKg, String temperament,
			String healthNotes, Boolean vaccinated) {
		/** Só os dados básicos (sem a ficha). */
		public Input(String name, String species, String breed, String size, String notes) {
			this(name, species, breed, size, notes, null, null, null, null, null);
		}
	}

	/** Dados válidos e normalizados, prontos para gravar. */
	public record Normalized(String name, PetSpecies species, String breed, PetSize size, String notes, PetCoat coat, BigDecimal weightKg,
			PetTemperament temperament, String healthNotes, Boolean vaccinated) {
	}

	public static Normalized normalize(Input in) {
		Map<String, String> errors = new LinkedHashMap<>();
		String name = line(in.name(), MAX_NAME, "name", errors);
		if (name == null) {
			errors.putIfAbsent("name", "obrigatório, até " + MAX_NAME + " caracteres");
		}
		PetSpecies species = parseEnum(PetSpecies.class, in.species());
		if (species == null) {
			errors.put("species", "obrigatório: DOG ou CAT");
		}
		String breed = line(in.breed(), MAX_BREED, "breed", errors);
		PetSize size = null;
		if (in.size() != null && !in.size().isBlank()) {
			size = parseEnum(PetSize.class, in.size().strip());
			if (size == null) {
				errors.put("size", "SMALL, MEDIUM ou LARGE");
			}
		}
		String notes = notes(in.notes(), MAX_NOTES, "notes", errors);
		PetCoat coat = optionalEnum(PetCoat.class, in.coat(), "coat", "SHORT, MEDIUM, LONG, CURLY ou DOUBLE", errors);
		PetTemperament temperament = optionalEnum(PetTemperament.class, in.temperament(), "temperament", "CALM, ANXIOUS ou REACTIVE", errors);
		BigDecimal weight = weight(in.weightKg(), errors);
		String healthNotes = notes(in.healthNotes(), MAX_HEALTH_NOTES, "healthNotes", errors);
		if (!errors.isEmpty()) {
			throw new PetExceptions.InvalidPetException(errors);
		}
		return new Normalized(name, species, breed, size, notes, coat, weight, temperament, healthNotes, in.vaccinated());
	}

	/** Nome e raça: uma linha só. Devolve nulo se vazio (o chamador decide se é obrigatório); erro de formato vai para {@code errors}. */
	private static String line(String raw, int max, String field, Map<String, String> errors) {
		if (raw == null) {
			return null;
		}
		String text = raw.strip();
		if (text.isEmpty()) {
			return null;
		}
		if (hasControl(text, false)) {
			errors.put(field, "caracteres inválidos");
			return null;
		}
		text = text.replaceAll(" {2,}", " ");
		if (text.codePointCount(0, text.length()) > max) {
			errors.put(field, "até " + max + " caracteres");
			return null;
		}
		return text;
	}

	/** Peso em kg: de 0,5 a 120,0, com no máximo uma casa decimal (o banco guarda numeric(4,1)). Nulo = não informado. */
	private static BigDecimal weight(BigDecimal raw, Map<String, String> errors) {
		if (raw == null) {
			return null;
		}
		BigDecimal value = raw.stripTrailingZeros();
		if (value.scale() > 1 || value.compareTo(MIN_WEIGHT) < 0 || value.compareTo(MAX_WEIGHT) > 0) {
			errors.put("weightKg", "de 0,5 a 120 kg, com no máximo uma casa decimal");
			return null;
		}
		return value.setScale(1);
	}

	/** Valor de lista fechada, opcional: vazio vira nulo; fora da lista é erro do campo. */
	private static <E extends Enum<E>> E optionalEnum(Class<E> type, String raw, String field, String allowed, Map<String, String> errors) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		E value = parseEnum(type, raw.strip());
		if (value == null) {
			errors.put(field, allowed);
		}
		return value;
	}

	private static String notes(String raw, int max, String field, Map<String, String> errors) {
		if (raw == null) {
			return null;
		}
		String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
		if (text.isEmpty()) {
			return null;
		}
		if (hasControl(text, true)) {
			errors.put(field, "caracteres inválidos");
			return null;
		}
		if (text.codePointCount(0, text.length()) > max) {
			errors.put(field, "até " + max + " caracteres");
			return null;
		}
		return text;
	}

	/** Controles C0 (exceto, se permitido, o LF), DEL e C1: o mesmo conjunto que o banco recusa. */
	private static boolean hasControl(String text, boolean allowNewline) {
		return text.codePoints().anyMatch(cp -> (cp < 0x20 && !(allowNewline && cp == '\n')) || (cp >= 0x7F && cp <= 0x9F));
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
		if (raw == null) {
			return null;
		}
		for (E value : type.getEnumConstants()) {
			if (value.name().equals(raw)) {
				return value;
			}
		}
		return null;
	}
}
