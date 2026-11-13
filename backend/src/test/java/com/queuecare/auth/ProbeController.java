package com.queuecare.auth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas SÓ DE TESTE para provar a autorização por perfil (R4). Não existem no código de produção. */
@RestController
class ProbeController {

	@GetMapping("/api/test/provider-only")
	@PreAuthorize("hasRole('PROVIDER')")
	String providerOnly() {
		return "ok";
	}

	@GetMapping("/api/test/staff")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	String staff() {
		return "ok";
	}
}
