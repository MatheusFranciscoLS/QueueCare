package com.queuecare.scheduling.domain;

/**
 * Resultado da checagem de tempo de um agendamento (regra R5). Independente de HTTP: a camada de API
 * traduz para respostas e mensagens.
 */
public enum BookingTimeCheck {

	/** O início respeita o passado e a antecedência mínima. */
	OK,

	/** O início é anterior ao momento atual. */
	IN_THE_PAST,

	/** O início ainda não passou, mas é mais cedo que a antecedência mínima exigida. */
	TOO_SOON
}
