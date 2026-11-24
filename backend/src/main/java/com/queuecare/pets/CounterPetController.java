package com.queuecare.pets;

import com.queuecare.auth.CounterCustomers;
import com.queuecare.pets.PetController.PetRequest;
import com.queuecare.pets.PetController.PetResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pets dos tutores cadastrados pelo balcão (ADR 0033). A equipe lista e cadastra, SÓ para tutor que o próprio estúdio criou (404 igual para qualquer outro alvo).
 * Editar e arquivar continuam do tutor: a equipe não mexe na ficha depois de criada por esta via.
 */
@RestController
@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
class CounterPetController {

	private final CounterCustomers counter;
	private final PetService pets;

	CounterPetController(CounterCustomers counter, PetService pets) {
		this.counter = counter;
		this.pets = pets;
	}

	@Operation(operationId = "listCounterCustomerPets")
	@GetMapping("/api/agenda/customers/{customerId}/pets")
	List<PetResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId) {
		UUID owner = counter.requireOwned(UUID.fromString(jwt.getSubject()), customerId).customer().getId();
		return pets.list(owner, false).stream().map(PetResponse::from).toList();
	}

	@Operation(operationId = "createCounterCustomerPet")
	@PostMapping("/api/agenda/customers/{customerId}/pets")
	@ResponseStatus(HttpStatus.CREATED)
	PetResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId, @RequestBody PetRequest request) {
		UUID owner = counter.requireOwned(UUID.fromString(jwt.getSubject()), customerId).customer().getId();
		return PetResponse.from(pets.create(owner, request.toInput()));
	}
}
