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

	/** Devolve o código de requisição que está no MDC DENTRO do tratamento da chamada (prova que o log da aplicação o enxerga). */
	@GetMapping("/api/test/mdc")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	String mdc() {
		org.slf4j.LoggerFactory.getLogger("probe-mdc").info("linha de log DENTRO da chamada");
		return String.valueOf(org.slf4j.MDC.get("requestId"));
	}

	@GetMapping("/api/test/staff")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	String staff() {
		return "ok";
	}
}
