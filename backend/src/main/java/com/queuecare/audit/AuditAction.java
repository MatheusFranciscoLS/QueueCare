package com.queuecare.audit;

/** Ações registradas na auditoria (coluna audit_log.action; o banco confere a lista com um CHECK). */
public enum AuditAction {
	ADMIN_BOOTSTRAPPED, PROVIDER_ACCOUNT_CREATED, ATTENDANT_ACCOUNT_CREATED, PASSWORD_CHANGED, PASSWORD_RESET, USER_DEACTIVATED, USER_ACTIVATED,
	/** ADR 0025: o dono do estúdio gerou uma senha temporária para um tutor que ele atendeu (ator = dono, alvo = tutor). */
	CUSTOMER_PASSWORD_RESET_BY_STUDIO,
	/** ADR 0027: o tutor excluiu a PRÓPRIA conta, que foi anonimizada no lugar (ator = alvo = o tutor). */
	ACCOUNT_ANONYMIZED,
	/** ADR 0033: a equipe cadastrou um tutor pelo balcão (ator = quem cadastrou, alvo = o tutor). */
	CUSTOMER_CREATED_BY_STUDIO
}
