package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.ProviderExceptions.InvalidConfigurationException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderAlreadyConfiguredException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.application.ProviderExceptions.SlugTakenException;
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
	static final Duration MIN_LOCK_TIMEOUT = Duration.ofMillis(1);
	static final Duration MAX_LOCK_TIMEOUT = Duration.ofSeconds(60);

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
		// Menos de 1 ms viraria 0 ms depois da conversão, e lock_timeout = 0 DESLIGA o limite no PostgreSQL (espera infinita);
		// negativo seria erro: tudo fora de 1 ms..60 s impede a aplicação de subir.
		if (lockTimeout.compareTo(MIN_LOCK_TIMEOUT) < 0 || lockTimeout.compareTo(MAX_LOCK_TIMEOUT) > 0) {
			throw new IllegalArgumentException("queuecare.scheduling.provider-lock-timeout deve estar entre 1 ms e 60 s");
		}
		this.lockTimeoutMillis = lockTimeout.toMillis();
	}

	/** Entrada de uma janela semanal em horário local do prestador. */
	public record WindowInput(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {
	}

	// --- negócio ---

	@Transactional
	public Provider createBusiness(UUID ownerUserId, String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes,
			String phone) {
		validateBusiness(businessName, timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
		String normalizedPhone = com.queuecare.auth.PhoneNumber.normalize(phone);
		if (providers.findByUserId(ownerUserId).isPresent()) {
			throw new ProviderAlreadyConfiguredException();
		}
		try {
			Provider created = new Provider(ownerUserId, businessName.trim(), timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
			created.setPhone(normalizedPhone);
			return providers.saveAndFlush(created);
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
	public Provider updateBusiness(UUID ownerUserId, String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes,
			String phone) {
		validateBusiness(businessName, timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
		String normalizedPhone = com.queuecare.auth.PhoneNumber.normalize(phone);
		Provider provider = lockBusiness(ownerUserId);
		provider.update(businessName.trim(), timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
		provider.setPhone(normalizedPhone);
		return providers.save(provider);
	}

	// --- sinal por Pix (ADR 0023) ---

	static final int MAX_DEPOSIT_CENTS = 10_000_000;
	static final int MAX_PIX_KEY_LENGTH = 140;

	/**
	 * Configura o sinal por Pix: chave, valor em centavos e quando pedir. Chave vazia limpa; a regra ligada exige chave E valor (o banco repete).
	 * A mensagem de erro nunca repete a chave (é dado do estúdio, mas pode ser CPF, telefone ou e-mail).
	 */
	@Transactional
	public Provider updateDeposit(UUID ownerUserId, String pixKey, Integer depositCents, String rule) {
		com.queuecare.scheduling.persistence.DepositRule parsed;
		try {
			parsed = com.queuecare.scheduling.persistence.DepositRule.valueOf(rule == null ? "" : rule.trim());
		} catch (IllegalArgumentException e) {
			throw new InvalidConfigurationException("depositRule", "uma das regras oferecidas");
		}
		String key = pixKey == null || pixKey.isBlank() ? null : pixKey.trim();
		if (key != null && (key.length() > MAX_PIX_KEY_LENGTH || key.codePoints().anyMatch(Character::isISOControl))) {
			throw new InvalidConfigurationException("pixKey", "até " + MAX_PIX_KEY_LENGTH + " caracteres, sem quebras de linha");
		}
		if (depositCents != null && (depositCents < 1 || depositCents > MAX_DEPOSIT_CENTS)) {
			throw new InvalidConfigurationException("depositCents", "entre 1 e " + MAX_DEPOSIT_CENTS + " centavos");
		}
		if (parsed != com.queuecare.scheduling.persistence.DepositRule.NEVER) {
			if (key == null) {
				throw new InvalidConfigurationException("pixKey", "informe a chave Pix para pedir sinal");
			}
			if (depositCents == null) {
				throw new InvalidConfigurationException("depositCents", "informe o valor do sinal para pedir sinal");
			}
		}
		Provider provider = lockBusiness(ownerUserId);
		provider.setDeposit(key, depositCents, parsed);
		return providers.save(provider);
	}

	// --- página pública (ADR 0015) ---

	static final java.util.Set<String> RESERVED_SLUGS = java.util.Set.of("admin", "api", "app", "entrar", "cadastro", "agendar", "agenda", "fila", "p", "pelo-e-ponto",
			"pelo-ponto", "queuecare", "servicos", "equipe", "expediente", "suporte", "ajuda", "termos", "privacidade", "login", "logout", "www");

	private static final java.util.regex.Pattern SLUG = java.util.regex.Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

	/** Define o endereço público e a publicação. Slug nulo/vazio só é aceito com a página NÃO publicada (limpa o endereço). */
	@Transactional
	public Provider updatePublicPage(UUID ownerUserId, String slug, boolean published) {
		String normalized = slug == null || slug.isBlank() ? null : slug.trim();
		if (normalized != null) {
			if (normalized.length() < 3 || normalized.length() > 40 || !SLUG.matcher(normalized).matches()) {
				throw new InvalidConfigurationException("slug", "de 3 a 40 caracteres: letras minúsculas, números e hífens entre grupos");
			}
			if (RESERVED_SLUGS.contains(normalized)) {
				throw new InvalidConfigurationException("slug", "endereço reservado");
			}
		}
		if (published && normalized == null) {
			throw new InvalidConfigurationException("slug", "escolha um endereço para publicar");
		}
		Provider provider = lockBusiness(ownerUserId);
		try {
			provider.setPublicPage(normalized, published);
			return providers.saveAndFlush(provider);
		} catch (DataIntegrityViolationException e) {
			// A restrição UNIQUE do banco decide quando dois estúdios disputam o mesmo endereço ao mesmo tempo. Só ESTE índice vira conflito de
			// endereço; qualquer outra violação de integridade continua sendo um erro de verdade (não é mascarada).
			if (isSlugUniqueViolation(e)) {
				throw new SlugTakenException();
			}
			throw e;
		}
	}

	private static boolean isSlugUniqueViolation(Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException violation && "providers_slug_key".equals(violation.getConstraintName())) {
				return true;
			}
			if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState()) && String.valueOf(sql.getMessage()).contains("providers_slug_key")) {
				return true;
			}
		}
		return false;
	}

	// --- serviços ---

	@Transactional(readOnly = true)
	public List<ServiceOffering> listServices(UUID ownerUserId) {
		return services.findByProviderIdOrderByName(getBusiness(ownerUserId).getId());
	}

	/** Durações por porte médio e grande (nulo = a base) e minutos a mais para pelo longo, cacheado ou duplo (nulo = 0). ADR 0020. */
	public record Durations(Integer medium, Integer large, Integer coatExtra) {
		public static final Durations NONE = new Durations(null, null, null);
	}

	/** Extra máximo de pelo, em minutos. O banco repete a faixa (services_coat_extra_check). */
	static final int MAX_COAT_EXTRA_MINUTES = 240;

	@Transactional
	public ServiceOffering createService(UUID ownerUserId, String name, int durationMinutes, Integer priceCents, Integer priceMediumCents, Integer priceLargeCents) {
		return createService(ownerUserId, name, durationMinutes, Durations.NONE, priceCents, priceMediumCents, priceLargeCents);
	}

	@Transactional
	public ServiceOffering createService(UUID ownerUserId, String name, int durationMinutes, Durations durations, Integer priceCents, Integer priceMediumCents,
			Integer priceLargeCents) {
		validateService(name, durationMinutes);
		validateDurations(durationMinutes, durations);
		validatePrices(priceCents, priceMediumCents, priceLargeCents);
		Provider provider = getBusiness(ownerUserId);
		ServiceOffering service = new ServiceOffering(provider.getId(), name.trim(), durationMinutes);
		service.setDurations(durations.medium(), durations.large(), durations.coatExtra() == null ? 0 : durations.coatExtra());
		service.setPrices(priceCents, priceMediumCents, priceLargeCents);
		return services.save(service);
	}

	@Transactional
	public ServiceOffering updateService(UUID ownerUserId, UUID serviceId, String name, int durationMinutes, boolean active, Integer priceCents,
			Integer priceMediumCents, Integer priceLargeCents) {
		return updateService(ownerUserId, serviceId, name, durationMinutes, Durations.NONE, active, priceCents, priceMediumCents, priceLargeCents);
	}

	@Transactional
	public ServiceOffering updateService(UUID ownerUserId, UUID serviceId, String name, int durationMinutes, Durations durations, boolean active,
			Integer priceCents, Integer priceMediumCents, Integer priceLargeCents) {
		validateService(name, durationMinutes);
		validateDurations(durationMinutes, durations);
		validatePrices(priceCents, priceMediumCents, priceLargeCents);
		Provider provider = getBusiness(ownerUserId);
		ServiceOffering service = services.findByIdAndProviderId(serviceId, provider.getId()).orElseThrow(ServiceNotFoundException::new);
		service.update(name.trim(), durationMinutes, active);
		service.setDurations(durations.medium(), durations.large(), durations.coatExtra() == null ? 0 : durations.coatExtra());
		service.setPrices(priceCents, priceMediumCents, priceLargeCents);
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

	/** Preços em centavos, de 0 a R$ 100.000,00; nulo = a combinar. O banco repete a faixa. */
	static final int MAX_PRICE_CENTS = 10_000_000;

	private static void validatePrices(Integer base, Integer medium, Integer large) {
		String[] fields = { "priceCents", "priceMediumCents", "priceLargeCents" };
		Integer[] values = { base, medium, large };
		for (int i = 0; i < values.length; i++) {
			if (values[i] != null && (values[i] < 0 || values[i] > MAX_PRICE_CENTS)) {
				throw new InvalidConfigurationException(fields[i], "entre 0 e " + MAX_PRICE_CENTS + " centavos");
			}
		}
	}

	/**
	 * Cada duração de porte fica na faixa do serviço; o extra de pelo vai de 0 a 240; e o PIOR caso (a maior duração de porte somada ao extra)
	 * não passa de 480 minutos, para que nenhum pet gere um atendimento fora da faixa.
	 */
	private static void validateDurations(int base, Durations d) {
		String[] fields = { "durationMediumMinutes", "durationLargeMinutes" };
		Integer[] values = { d.medium(), d.large() };
		int longest = base;
		for (int i = 0; i < values.length; i++) {
			if (values[i] != null) {
				if (values[i] < MIN_SERVICE_MINUTES || values[i] > MAX_SERVICE_MINUTES) {
					throw new InvalidConfigurationException(fields[i], "entre " + MIN_SERVICE_MINUTES + " e " + MAX_SERVICE_MINUTES);
				}
				longest = Math.max(longest, values[i]);
			}
		}
		int extra = d.coatExtra() == null ? 0 : d.coatExtra();
		if (extra < 0 || extra > MAX_COAT_EXTRA_MINUTES) {
			throw new InvalidConfigurationException("coatExtraMinutes", "entre 0 e " + MAX_COAT_EXTRA_MINUTES);
		}
		if (longest + extra > MAX_SERVICE_MINUTES) {
			throw new InvalidConfigurationException("coatExtraMinutes", "a maior duração somada ao extra de pelo não pode passar de " + MAX_SERVICE_MINUTES + " minutos");
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
