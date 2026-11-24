package com.queuecare.scheduling.web;

import com.queuecare.auth.PasswordService;
import com.queuecare.scheduling.application.StudioCustomerService;
import com.queuecare.scheduling.application.StudioCustomerService.CustomerView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Os clientes do estúdio (ADR 0025). A busca é da equipe (dono e atendente) e só mostra tutores que o estúdio já atendeu; a senha temporária é SÓ do dono.
 */
@RestController
class StudioCustomersController {

	private final StudioCustomerService customers;
	private final PasswordService passwords;

	StudioCustomersController(StudioCustomerService customers, PasswordService passwords) {
		this.customers = customers;
		this.passwords = passwords;
	}

	/** {@code visits}: atendimentos concluídos; {@code lastAppointmentAt}: início do agendamento mais recente, de qualquer estado. */
	record CustomerResponse(UUID id, String name, String phone, long visits, long noShows, Instant lastAppointmentAt, List<String> pets, boolean byCounter, boolean hasEmail) {
		static CustomerResponse from(CustomerView v) {
			return new CustomerResponse(v.id(), v.name(), v.phone(), v.visits(), v.noShows(), v.lastAppointmentAt(), v.pets(), v.byCounter(), v.hasEmail());
		}
	}

	record TemporaryPasswordResponse(String temporaryPassword) {
	}

	@GetMapping("/api/agenda/customers")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	List<CustomerResponse> search(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false, defaultValue = "") String q) {
		return customers.search(UUID.fromString(jwt.getSubject()), q).stream().map(CustomerResponse::from).toList();
	}

	/** A senha aparece SÓ nesta resposta (não pode ficar em cache nem em proxy). */
	@PostMapping("/api/provider/customers/{customerId}/reset-password")
	@PreAuthorize("hasRole('PROVIDER')")
	ResponseEntity<TemporaryPasswordResponse> resetPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId) {
		String temporary = passwords.resetCustomerPasswordByStudio(UUID.fromString(jwt.getSubject()), customerId);
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new TemporaryPasswordResponse(temporary));
	}
}
