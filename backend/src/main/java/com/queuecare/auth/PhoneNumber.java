package com.queuecare.auth;

/**
 * Telefone brasileiro normalizado (ADR 0019, fatia 1). Aceita o que a pessoa digita (com máscara, parênteses, traço, espaços, "+55" ou sem
 * DDI) e devolve SÓ DÍGITOS com DDI: {@code 55 + DDD + número}, 12 ou 13 dígitos. O banco repete a forma final (users_phone_check).
 *
 * <p>Regras: DDD de 11 a 99 (nenhum dígito zero); número de 9 dígitos começa com 9 (celular); número de 8 dígitos começa de 2 a 5 (fixo).
 * Vazio é "não informado" (nulo). A mensagem de erro nunca repete o valor recebido (telefone é dado pessoal).
 */
public final class PhoneNumber {

	static final int MAX_INPUT = 40;

	private PhoneNumber() {
	}

	/** Devolve só dígitos com DDI, ou nulo se vazio. Lança {@link AuthExceptions.InvalidPhoneException} se o formato não é de telefone do Brasil. */
	public static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = raw.strip();
		if (text.length() > MAX_INPUT || !text.matches("[0-9 ()+.\\-]+") || text.indexOf('+', 1) >= 0) {
			throw new AuthExceptions.InvalidPhoneException();
		}
		String digits = text.replaceAll("[^0-9]", "");
		boolean withCountry = (digits.length() == 12 || digits.length() == 13) && digits.startsWith("55");
		if (!withCountry) {
			if (text.startsWith("+") || digits.length() < 10 || digits.length() > 11) {
				throw new AuthExceptions.InvalidPhoneException();
			}
			digits = "55" + digits;
		}
		String area = digits.substring(2, 4);
		String number = digits.substring(4);
		boolean areaOk = area.charAt(0) != '0' && area.charAt(1) != '0';
		boolean numberOk = number.length() == 9 ? number.charAt(0) == '9' : number.charAt(0) >= '2' && number.charAt(0) <= '5';
		if (!areaOk || !numberOk) {
			throw new AuthExceptions.InvalidPhoneException();
		}
		return digits;
	}
}
