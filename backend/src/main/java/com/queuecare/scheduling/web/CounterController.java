package com.queuecare.scheduling.web;

import com.queuecare.auth.CounterCustomers;
import com.queuecare.auth.CounterImport;
import com.queuecare.scheduling.application.CounterBooking;
import com.queuecare.scheduling.application.StudioCustomerService;
import com.queuecare.scheduling.application.SlotService.Slot;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.UserAccount;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro e reserva pelo balcão (ADR 0033). Equipe (dono e atendente), SEMPRE por tutores que o próprio estúdio cadastrou: o estúdio vem do vínculo da equipe no banco
 * e nunca de parâmetro, e tutor que não é do estúdio responde 404 igual a tutor inexistente.
 */
@RestController
@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
class CounterController {

	private final CounterCustomers customers;
	private final CounterBooking booking;
	private final CounterImport importer;

	CounterController(CounterCustomers customers, CounterBooking booking, CounterImport importer) {
		this.customers = customers;
		this.booking = booking;
		this.importer = importer;
	}

	/** {@code phone} obrigatório (é como o estúdio fala com o tutor); {@code email} opcional: sem ele a conta não tem como entrar no app. */
	record CreateCustomerRequest(@NotBlank @Size(max = 120) String name, String phone, @Email @Size(max = 254) String email) {
	}

	record CounterCustomerResponse(UUID id, String name, String phone, boolean hasEmail) {
		static CounterCustomerResponse from(UserAccount u) {
			return new CounterCustomerResponse(u.getId(), u.getName(), u.getPhone(), StudioCustomerService.hasEmail(u));
		}
	}

	@Operation(operationId = "listCounterServices")
	@GetMapping("/api/agenda/services")
	List<CatalogController.ServiceResponse> services(@AuthenticationPrincipal Jwt jwt) {
		return booking.services(UUID.fromString(jwt.getSubject())).stream().map(CatalogController.ServiceResponse::from).toList();
	}

	@Operation(operationId = "createCounterCustomer")
	@PostMapping("/api/agenda/customers")
	@ResponseStatus(HttpStatus.CREATED)
	CounterCustomerResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCustomerRequest request) {
		return CounterCustomerResponse.from(customers.create(UUID.fromString(jwt.getSubject()), request.name(), request.phone(), request.email()));
	}

	record SlotResponse(LocalDateTime localStart, Instant startsAt, Instant endsAt) {
		static SlotResponse from(Slot s) {
			return new SlotResponse(s.localStart(), s.startsAt(), s.endsAt());
		}
	}

	@Operation(operationId = "listCounterSlots")
	@GetMapping("/api/agenda/customers/{customerId}/slots")
	List<SlotResponse> slots(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId, @RequestParam UUID serviceId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam(required = false) UUID petId) {
		return booking.freeSlots(UUID.fromString(jwt.getSubject()), customerId, serviceId, date, petId).stream().map(SlotResponse::from).toList();
	}

	record CounterBookingRequest(@NotNull UUID serviceId, @NotNull LocalDateTime localStart, UUID petId) {
	}

	record CounterAppointmentResponse(UUID id, Instant startsAt, Instant endsAt, String status, Integer priceCents, Integer depositCents, String depositStatus) {
		static CounterAppointmentResponse from(Appointment a) {
			return new CounterAppointmentResponse(a.getId(), a.getStartsAt(), a.getEndsAt(), a.getStatus().name(), a.getPriceCents(), a.getDepositCents(), a.getDepositStatus().name());
		}
	}

	@Operation(operationId = "bookForCounterCustomer")
	@PostMapping("/api/agenda/customers/{customerId}/appointments")
	@ResponseStatus(HttpStatus.CREATED)
	CounterAppointmentResponse book(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId, @Valid @RequestBody CounterBookingRequest request) {
		return CounterAppointmentResponse.from(booking.book(UUID.fromString(jwt.getSubject()), customerId, request.serviceId(), request.localStart(), request.petId()));
	}

	/** Uma linha da planilha, já interpretada pela tela; {@code petSpecies} é DOG ou CAT (qualquer outra coisa conta como não reconhecida). */
	record ImportRow(@Size(max = 200) String name, @Size(max = 40) String phone, @Size(max = 254) String email, @Size(max = 200) String petName, @Size(max = 20) String petSpecies) {
	}

	record ImportRequest(@NotNull @Size(max = CounterImport.MAX_ROWS) List<@Valid ImportRow> rows) {
	}

	/** O resultado por linha diz só o número e o motivo, NUNCA o que veio na planilha (nome, telefone e e-mail são dados pessoais). */
	record ImportRowResponse(int row, String status, boolean petCreated, boolean petSkipped) {
	}

	record ImportResponse(int created, int duplicates, int invalid, int emailTaken, int petsCreated, int petsSkipped, List<ImportRowResponse> rows) {
	}

	@Operation(operationId = "importCounterCustomers")
	@PostMapping("/api/agenda/customers/import")
	@PreAuthorize("hasRole('PROVIDER')")
	ImportResponse importCustomers(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ImportRequest request) {
		var result = importer.run(UUID.fromString(jwt.getSubject()),
				request.rows().stream().map(r -> new CounterImport.Row(r.name(), r.phone(), r.email(), r.petName(), r.petSpecies())).toList());
		return new ImportResponse(result.created(), result.duplicates(), result.invalid(), result.emailTaken(), result.petsCreated(), result.petsSkipped(),
				result.rows().stream().map(r -> new ImportRowResponse(r.row(), r.status().name(), r.petCreated(), r.petSkipped())).toList());
	}
}
