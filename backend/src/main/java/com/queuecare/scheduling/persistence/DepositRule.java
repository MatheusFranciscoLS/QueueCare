package com.queuecare.scheduling.persistence;

/** Quando o estúdio pede sinal por Pix (ADR 0023). Padrão: NEVER. O banco repete a lista (providers_deposit_rule_check). */
public enum DepositRule {
	/** Nunca pede. */
	NEVER,
	/** Pede de quem ainda não teve atendimento concluído NESTE estúdio. */
	NEW_CUSTOMERS,
	/** Também pede de quem já faltou 2 ou mais vezes NESTE estúdio. */
	NEW_OR_REPEAT_NO_SHOW
}
