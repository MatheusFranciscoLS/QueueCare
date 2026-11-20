package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Formato do telefone (ADR 0019, fatia 1): o que a pessoa digita vira só dígitos com DDI; o que não é telefone do Brasil é recusado. */
class PhoneNumberTest {

	@ParameterizedTest
	@ValueSource(strings = { "(11) 91234-5678", "11912345678", "+55 11 91234-5678", "55 (11) 91234-5678", "5511912345678", " 11 91234 5678 ", "11.91234.5678" })
	void celularComOuSemMascaraEComOuSemDdiViraDigitosComDdi(String typed) {
		assertEquals("5511912345678", PhoneNumber.normalize(typed));
	}

	@Test
	void fixoDeOitoDigitos() {
		assertEquals("551132345678", PhoneNumber.normalize("(11) 3234-5678"));
	}

	@Test
	void ddd55ComCelularNaoConfundeComDdi() {
		assertEquals("5555999998888", PhoneNumber.normalize("55999998888"));
	}

	@Test
	void vazioEhNaoInformado() {
		assertNull(PhoneNumber.normalize(null));
		assertNull(PhoneNumber.normalize(""));
		assertNull(PhoneNumber.normalize("   "));
	}

	@ParameterizedTest
	@ValueSource(strings = { "123", "1191234567", "(01) 91234-5678", "(11) 81234-5678", "(11) 1234-5678", "+1 415 555 2671", "+11912345678", "abc", "(11) 91234-5678 ramal 2", "1191234567890123", "55 11 9123-45678-9", "++5511912345678", "5511+912345678" })
	void naoEhTelefoneDoBrasil(String typed) {
		assertThrows(AuthExceptions.InvalidPhoneException.class, () -> PhoneNumber.normalize(typed));
	}
}
