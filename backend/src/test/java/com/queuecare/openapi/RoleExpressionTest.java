package com.queuecare.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** O gerador só descreve expressões que entende; qualquer outra FALHA a geração em vez de publicar uma descrição enganosa. */
class RoleExpressionTest {

	@Test
	void entendeHasRoleEHasAnyRole() {
		assertEquals(List.of("ADMIN"), OpenApiConfig.parseRoles("hasRole('ADMIN')"));
		assertEquals(List.of("PROVIDER", "ATTENDANT"), OpenApiConfig.parseRoles("hasAnyRole('PROVIDER','ATTENDANT')"));
		assertEquals(List.of("CUSTOMER", "PROVIDER", "ATTENDANT"), OpenApiConfig.parseRoles("  hasAnyRole( 'CUSTOMER' , 'PROVIDER', 'ATTENDANT' ) "));
	}

	@Test
	void expressaoDesconhecidaFalhaEmVezDeDescreverErrado() {
		for (String unsupported : new String[] { "permitAll()", "isAuthenticated()", "hasAuthority('ROLE_ADMIN')", "hasRole('ADMIN') or hasRole('PROVIDER')",
				"hasRole('ADMIN') and #id == principal.id", "hasRole(@perfil.admin())", "hasAnyRole()", "" }) {
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> OpenApiConfig.parseRoles(unsupported), unsupported);
			assertEquals(true, e.getMessage().contains("não suportada"), e.getMessage());
		}
	}
}
