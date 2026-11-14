package com.queuecare.scheduling.persistence;

/** Perfis de usuário (coluna users.role). ADMIN administra contas e NÃO acessa agendas nem dados de clientes. */
public enum UserRole {
	ADMIN, PROVIDER, ATTENDANT, CUSTOMER
}
