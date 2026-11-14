package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.BookingRejectedException.Reason;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de leitura para o CLIENTE escolher onde agendar. Expõe só o mínimo público: id, nome do negócio e fuso do prestador;
 * id, nome e duração dos serviços ativos. Nada de e-mail, usuário dono, configurações internas nem serviços desativados, e
 * prestador com dono desativado nunca aparece (nem pelo id direto: 404, igual a inexistente).
 */
@Service
public class CatalogService {

	/** Prestador visível ao cliente. */
	public record ProviderView(UUID id, String businessName, String timeZone) {
	}

	/** Serviço ativo visível ao cliente. */
	public record ServiceView(UUID id, String name, int durationMinutes) {
	}

	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;

	public CatalogService(ProviderRepository providers, ServiceOfferingRepository services) {
		this.providers = providers;
		this.services = services;
	}

	@Transactional(readOnly = true)
	public List<ProviderView> providers(int page, int size) {
		return providers.findAllActive(PageRequest.of(page, size)).stream().map(p -> new ProviderView(p.getId(), p.getBusinessName(), p.getTimeZone())).toList();
	}

	@Transactional(readOnly = true)
	public List<ServiceView> services(UUID providerId) {
		providers.findActiveById(providerId).orElseThrow(() -> new BookingRejectedException(Reason.PROVIDER_NOT_FOUND));
		return services.findByProviderIdAndActiveTrueOrderByNameAscIdAsc(providerId).stream()
				.map(s -> new ServiceView(s.getId(), s.getName(), s.getDurationMinutes())).toList();
	}
}
