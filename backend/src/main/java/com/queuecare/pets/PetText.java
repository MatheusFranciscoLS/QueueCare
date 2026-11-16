package com.queuecare.pets;

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
 * </ul>
 * Tamanho é contado em pontos de código (como o {@code char_length} do PostgreSQL), não em unidades UTF-16. As mensagens de erro
 * nunca repetem o valor recebido (as observações são dado pessoal).
 */
public final class PetText {

	static final int MAX_NAME = 80;
	static final int MAX_BREED = 80;
	static final int MAX_NOTES = 500;

	private PetText() {
	}

	/** Entrada crua (como veio da API). */
	public record Input(String name, String species, String breed, String size, String notes) {
	}

	/** Dados válidos e normalizados, prontos para gravar. */
	public record Normalized(String name, PetSpecies species, String breed, PetSize size, String notes) {
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
		String notes = notes(in.notes(), errors);
		if (!errors.isEmpty()) {
			throw new PetExceptions.InvalidPetException(errors);
		}
		return new Normalized(name, species, breed, size, notes);
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

	private static String notes(String raw, Map<String, String> errors) {
		if (raw == null) {
			return null;
		}
		String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
		if (text.isEmpty()) {
			return null;
		}
		if (hasControl(text, true)) {
			errors.put("notes", "caracteres inválidos");
			return null;
		}
		if (text.codePointCount(0, text.length()) > MAX_NOTES) {
			errors.put("notes", "até " + MAX_NOTES + " caracteres");
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
