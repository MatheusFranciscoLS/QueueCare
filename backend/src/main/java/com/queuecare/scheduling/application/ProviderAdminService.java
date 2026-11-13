package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.ProviderExceptions.InvalidConfigurationException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderAlreadyConfiguredException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.application.ProviderExceptions.ServiceNotFoundException;
import com.queuecare.scheduling.domain.AvailabilityPolicy;
import com.queuecare.scheduling.domain.AvailabilityWindow;
import com.queuecare.scheduling.persistence.AvailabilityWindowEntity;
import com.queuecare.scheduling.persistence.AvailabilityWindowRepository;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuração do PRÓPRIO negócio pelo prestador: dados do negócio, serviços e janelas de atendimento.
 * Todas as operações recebem o id do usuário autenticado (vindo do token) e só enxergam o negócio dele: serviço de
 * outro prestador responde como "não encontrado". Os limites de valores ficam aqui; o banco tem as mesmas garantias
 * básicas (CHECKs, FKs, janelas sem sobreposição) como defesa final.
 */
@Service
public class ProviderAdminService {

	static final int MAX_MINUTES_SETTING = 7 * 24 * 60;
	static final int MIN_SERVICE_MINUTES = 5;
	static final int MAX_SERVICE_MINUTES = 480;
	static final int MAX_WINDOWS = 70;

	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final AvailabilityWindowRepository windows;

	public ProviderAdminService(ProviderRepository providers, ServiceOfferingRepository services, AvailabilityWindowRepository windows) {
		this.providers = providers;
		this.services = services;
		this.windows = windows;
	}

	/** Entrada de uma janela semanal em horário local do prestador. */
	public record WindowInput(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {
	}

	// --- negócio ---

	@Transactional
	public Provider createBusiness(UUID ownerUserId, String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes) {
		validateBusiness(businessName, timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
		if (providers.findByUserId(ownerUserId).isPresent()) {
			throw new ProviderAlreadyConfiguredException();
		}
		try {
			return providers.saveAndFlush(new Provider(ownerUserId, businessName.trim(), timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes));
		} catch (DataIntegrityViolationException e) {
			// Dois cadastros simultâneos do mesmo usuário: a restrição UNIQUE(user_id) do banco decide.
			throw new ProviderAlreadyConfiguredException();
		}
	}

	@Transactional(readOnly = true)
	public Provider getBusiness(UUID ownerUserId) {
		return providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
	}

	@Transactional
	public Provider updateBusiness(UUID ownerUserId, String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes) {
		validateBusiness(businessName, timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
		Provider provider = getBusiness(ownerUserId);
		provider.update(businessName.trim(), timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
		return providers.save(provider);
	}

	// --- serviços ---

	@Transactional(readOnly = true)
	public List<ServiceOffering> listServices(UUID ownerUserId) {
		return services.findByProviderIdOrderByName(getBusiness(ownerUserId).getId());
	}

	@Transactional
	public ServiceOffering createService(UUID ownerUserId, String name, int durationMinutes) {
		validateService(name, durationMinutes);
		Provider provider = getBusiness(ownerUserId);
		return services.save(new ServiceOffering(provider.getId(), name.trim(), durationMinutes));
	}

	@Transactional
	public ServiceOffering updateService(UUID ownerUserId, UUID serviceId, String name, int durationMinutes, boolean active) {
		validateService(name, durationMinutes);
		Provider provider = getBusiness(ownerUserId);
		ServiceOffering service = services.findByIdAndProviderId(serviceId, provider.getId()).orElseThrow(ServiceNotFoundException::new);
		service.update(name.trim(), durationMinutes, active);
		return services.save(service);
	}

	// --- janelas de atendimento ---

	@Transactional(readOnly = true)
	public List<AvailabilityWindowEntity> getAvailability(UUID ownerUserId) {
		return windows.findByProviderId(getBusiness(ownerUserId).getId());
	}

	/** Substitui TODAS as janelas do prestador pela lista enviada (lista vazia = não atende em nenhum dia). */
	@Transactional
	public List<AvailabilityWindowEntity> replaceAvailability(UUID ownerUserId, List<WindowInput> inputs) {
		Provider provider = getBusiness(ownerUserId);
		if (inputs.size() > MAX_WINDOWS) {
			throw new InvalidConfigurationException("windows", "no máximo " + MAX_WINDOWS + " janelas");
		}
		List<AvailabilityWindow> domainWindows;
		try {
			domainWindows = inputs.stream().map(w -> new AvailabilityWindow(w.dayOfWeek(), w.start(), w.end())).toList();
			// A política valida sobreposição no mesmo dia (janelas coladas são aceitas).
			new AvailabilityPolicy(ZoneId.of(provider.getTimeZone()), domainWindows);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new InvalidConfigurationException("windows", "janelas inválidas ou sobrepostas no mesmo dia");
		}
		windows.deleteAllByProviderId(provider.getId());
		windows.flush();
		return windows.saveAll(domainWindows.stream()
				.map(w -> new AvailabilityWindowEntity(provider.getId(), w.dayOfWeek(), w.start(), w.end()))
				.toList());
	}

	// --- validações ---

	private static void validateBusiness(String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes) {
		if (businessName == null || businessName.isBlank() || businessName.trim().length() > 160) {
			throw new InvalidConfigurationException("businessName", "obrigatório, até 160 caracteres");
		}
		if (timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
			throw new InvalidConfigurationException("timeZone", "fuso IANA inválido (ex.: America/Sao_Paulo)");
		}
		if (minimumNoticeMinutes < 0 || minimumNoticeMinutes > MAX_MINUTES_SETTING) {
			throw new InvalidConfigurationException("minimumNoticeMinutes", "entre 0 e " + MAX_MINUTES_SETTING);
		}
		if (cancellationDeadlineMinutes < 0 || cancellationDeadlineMinutes > MAX_MINUTES_SETTING) {
			throw new InvalidConfigurationException("cancellationDeadlineMinutes", "entre 0 e " + MAX_MINUTES_SETTING);
		}
	}

	private static void validateService(String name, int durationMinutes) {
		if (name == null || name.isBlank() || name.trim().length() > 120) {
			throw new InvalidConfigurationException("name", "obrigatório, até 120 caracteres");
		}
		if (durationMinutes < MIN_SERVICE_MINUTES || durationMinutes > MAX_SERVICE_MINUTES) {
			throw new InvalidConfigurationException("durationMinutes", "entre " + MIN_SERVICE_MINUTES + " e " + MAX_SERVICE_MINUTES);
		}
	}
}
