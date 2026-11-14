package com.queuecare.audit;

/** Ações registradas na auditoria (coluna audit_log.action; o banco confere a lista com um CHECK). */
public enum AuditAction {
	ADMIN_BOOTSTRAPPED, PROVIDER_ACCOUNT_CREATED, ATTENDANT_ACCOUNT_CREATED, PASSWORD_CHANGED, PASSWORD_RESET, USER_DEACTIVATED, USER_ACTIVATED
}
