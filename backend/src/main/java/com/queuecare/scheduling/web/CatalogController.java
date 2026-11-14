package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.CatalogService;
import com.queuecare.scheduling.application.CatalogService.ProviderView;
import com.queuecare.scheduling.application.CatalogService.ServiceView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Catálogo para o cliente escolher prestador e serviço. SOMENTE o perfil CUSTOMER (rota e anotação). */
@RestController
@RequestMapping("/api/catalog")
@PreAuthorize("hasRole('CUSTOMER')")
class CatalogController {

	static final int MAX_PAGE_SIZE = 50;

	private final CatalogService catalog;

	CatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	record ProviderResponse(UUID id, String businessName, String timeZone) {
		static ProviderResponse from(ProviderView v) {
			return new ProviderResponse(v.id(), v.businessName(), v.timeZone());
		}
	}

	record ServiceResponse(UUID id, String name, int durationMinutes) {
		static ServiceResponse from(ServiceView v) {
			return new ServiceResponse(v.id(), v.name(), v.durationMinutes());
		}
	}

	@GetMapping("/providers")
	List<ProviderResponse> providers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paginação inválida");
		}
		return catalog.providers(page, size).stream().map(ProviderResponse::from).toList();
	}

	@GetMapping("/providers/{providerId}/services")
	List<ServiceResponse> services(@PathVariable UUID providerId) {
		return catalog.services(providerId).stream().map(ServiceResponse::from).toList();
	}
}
