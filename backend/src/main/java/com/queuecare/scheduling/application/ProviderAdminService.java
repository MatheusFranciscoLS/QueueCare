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
import java.time.Duration;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
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
	private final EntityManager entityManager;
	private final long lockTimeoutMillis;

	public ProviderAdminService(ProviderRepository providers, ServiceOfferingRepository services, AvailabilityWindowRepository windows,
			EntityManager entityManager, @Value("${queuecare.scheduling.provider-lock-timeout:3s}") Duration lockTimeout) {
		this.providers = providers;
		this.services = services;
		this.windows = windows;
		this.entityManager = entityManager;
		this.lockTimeoutMillis = lockTimeout.toMillis();
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
		Provider provider = lockBusiness(ownerUserId);
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

	/**
	 * Substitui TODAS as janelas do prestador pela lista enviada (lista vazia = não atende em nenhum dia). Dois PUTs
	 * simultâneos são serializados pelo bloqueio da linha do negócio: vale o último a confirmar e o resultado é sempre
	 * exatamente um dos conjuntos enviados, nunca uma mistura.
	 */
	@Transactional
	public List<AvailabilityWindowEntity> replaceAvailability(UUID ownerUserId, List<WindowInput> inputs) {
		Provider provider = lockBusiness(ownerUserId);
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

	/**
	 * Carrega o negócio do dono BLOQUEANDO a linha: edições concorrentes do mesmo prestador acontecem uma de cada vez.
	 * Regras do bloqueio (docs/estudo/configuracao-e-horarios-livres.md): (1) é o ÚNICO bloqueio pessimista da aplicação e é o PRIMEIRO passo da transação, antes de
	 * tocar em qualquer outra tabela, então não há ordem de bloqueios diferente entre caminhos (sem ciclo, sem deadlock por
	 * ordem); (2) a espera é LIMITADA por lock_timeout (só desta transação, via set_config(..., true)): se passar,
	 * o PostgreSQL aborta com 55P03 e a API responde 409 "recurso ocupado, tente novamente", nunca 500 nem espera infinita;
	 * (3) um deadlock (40P01), se algum dia ocorrer, tem a mesma resposta 409; (4) o bloqueio cai no commit ou rollback.
	 */
	private Provider lockBusiness(UUID ownerUserId) {
		entityManager.createNativeQuery("select set_config('lock_timeout', ?1, true)")
				.setParameter(1, lockTimeoutMillis + "ms")
				.getSingleResult();
		return providers.findByUserIdForUpdate(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
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
