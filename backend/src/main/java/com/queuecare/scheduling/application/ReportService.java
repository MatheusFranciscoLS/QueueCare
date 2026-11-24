package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.ProviderExceptions.InvalidConfigurationException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O resumo do estúdio (ADR 0031): o que o dono consulta para saber como o negócio vai, SEM virar um sistema financeiro. Só o DONO lê, só do PRÓPRIO estúdio, e todo
 * número é contado de agendamentos que já existem (nada novo é gravado).
 *
 * <p>Os valores NÃO são "faturamento": são {@code price_cents} congelado na reserva. Por isso cada valor tem um nome que diz o que é: o dos atendimentos CONCLUÍDOS
 * (o que o estúdio atendeu), o PREVISTO (marcado ou em andamento, ainda não concluído) e o dos sinais por Pix marcados como RECEBIDOS. Cancelado e falta nunca entram em
 * valor. O dia de cada atendimento é o do FUSO do estúdio, não o do servidor.
 */
@Service
public class ReportService {

	static final int MAX_DAYS = 366;
	static final int MAX_LAPSED = 30;
	static final int MAX_NO_SHOW_ROWS = 5;
	static final int MIN_LAPSED_DAYS = 14;
	static final int MAX_LAPSED_DAYS = 730;

	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final UserAccountRepository users;
	private final EntityManager entityManager;
	private final Clock clock;

	public ReportService(ProviderRepository providers, ServiceOfferingRepository services, UserAccountRepository users, EntityManager entityManager, Clock clock) {
		this.providers = providers;
		this.services = services;
		this.users = users;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/** Quantos atendimentos há em cada situação no período. {@code inProgress}: chegou, em atendimento ou pronto (ainda não concluído). */
	public record Counts(long completed, long noShow, long cancelled, long scheduled, long inProgress) {
	}

	/** Valores em centavos. Ver a explicação da classe: nenhum deles é "faturamento". */
	public record Amounts(long completedCents, long expectedCents, long depositsReceivedCents) {
	}

	public record ServiceRow(String service, long completed, long completedCents) {
	}

	public record DayRow(LocalDate date, long completed, long noShow, long cancelled) {
	}

	public record NoShowRow(UUID customerId, String name, long noShows) {
	}

	public record Summary(LocalDate from, LocalDate to, Counts counts, Amounts amounts, List<ServiceRow> byService, List<DayRow> byDay, List<NoShowRow> topNoShows) {
	}

	public record LapsedRow(UUID customerId, String name, String phone, Instant lastVisitAt, long daysSince) {
	}

	@Transactional(readOnly = true)
	public Summary summary(UUID ownerUserId, LocalDate from, LocalDate to) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		if (from == null || to == null) {
			throw new InvalidConfigurationException("dates", "informe o primeiro e o último dia");
		}
		if (to.isBefore(from)) {
			throw new InvalidConfigurationException("dates", "o último dia não pode ser antes do primeiro");
		}
		if (from.plusDays(MAX_DAYS).isBefore(to)) {
			throw new InvalidConfigurationException("dates", "o período tem no máximo " + MAX_DAYS + " dias");
		}
		ZoneId zone = ZoneId.of(provider.getTimeZone());
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

		// por dia e situação
		Map<LocalDate, long[]> days = new TreeMap<>();
		long completed = 0, noShow = 0, cancelled = 0, scheduled = 0, inProgress = 0;
		long completedCents = 0, expectedCents = 0;
		for (Object[] row : rows("select to_char(a.starts_at at time zone ?1, 'YYYY-MM-DD'), a.status, count(*), coalesce(sum(a.price_cents), 0) from appointments a "
				+ "where a.provider_id = ?2 and a.starts_at >= ?3 and a.starts_at < ?4 group by 1, 2", provider.getTimeZone(), provider.getId(), start, end)) {
			LocalDate day = LocalDate.parse((String) row[0]);
			String status = (String) row[1];
			long n = ((Number) row[2]).longValue();
			long cents = ((Number) row[3]).longValue();
			long[] d = days.computeIfAbsent(day, k -> new long[3]);
			switch (status) {
				case "COMPLETED" -> {
					completed += n;
					completedCents += cents;
					d[0] += n;
				}
				case "NO_SHOW" -> {
					noShow += n;
					d[1] += n;
				}
				case "CANCELLED" -> {
					cancelled += n;
					d[2] += n;
				}
				case "SCHEDULED" -> {
					scheduled += n;
					expectedCents += cents;
				}
				default -> { // CHECKED_IN, IN_PROGRESS, READY
					inProgress += n;
					expectedCents += cents;
				}
			}
		}
		List<DayRow> byDay = new ArrayList<>();
		LocalDate cursor = from;
		while (!cursor.isAfter(to)) { // todos os dias do período, inclusive os sem atendimento (a série não pula dias)
			long[] d = days.getOrDefault(cursor, new long[3]);
			byDay.add(new DayRow(cursor, d[0], d[1], d[2]));
			cursor = cursor.plusDays(1);
		}

		// por serviço (só concluídos)
		List<Object[]> serviceRows = rows("select a.service_id, count(*), coalesce(sum(a.price_cents), 0) from appointments a where a.provider_id = ?1 and a.status = 'COMPLETED' "
				+ "and a.starts_at >= ?2 and a.starts_at < ?3 group by 1", provider.getId(), start, end);
		Map<UUID, ServiceOffering> offerings = services.findAllById(serviceRows.stream().map(r -> (UUID) r[0]).toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, Function.identity()));
		List<ServiceRow> byService = serviceRows.stream()
				.map(r -> new ServiceRow(offerings.containsKey((UUID) r[0]) ? offerings.get((UUID) r[0]).getName() : "Serviço removido", ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
				.sorted(Comparator.comparingLong(ServiceRow::completed).reversed().thenComparing(ServiceRow::service)).toList();

		// faltas por tutor
		List<Object[]> missed = rows("select a.customer_id, count(*) from appointments a where a.provider_id = ?1 and a.status = 'NO_SHOW' and a.starts_at >= ?2 and a.starts_at < ?3 "
				+ "group by 1 order by 2 desc, 1 limit " + MAX_NO_SHOW_ROWS, provider.getId(), start, end);
		Map<UUID, UserAccount> names = users.findAllById(missed.stream().map(r -> (UUID) r[0]).toList()).stream().collect(Collectors.toMap(UserAccount::getId, Function.identity()));
		List<NoShowRow> topNoShows = missed.stream().map(r -> new NoShowRow((UUID) r[0], names.containsKey((UUID) r[0]) ? names.get((UUID) r[0]).getName() : "Cliente", ((Number) r[1]).longValue())).toList();

		long deposits = ((Number) rows("select coalesce(sum(a.deposit_cents), 0) from appointments a where a.provider_id = ?1 and a.deposit_status = 'RECEIVED' and a.starts_at >= ?2 and a.starts_at < ?3",
				provider.getId(), start, end).get(0)[0]).longValue();

		return new Summary(from, to, new Counts(completed, noShow, cancelled, scheduled, inProgress), new Amounts(completedCents, expectedCents, deposits), byService, byDay, topNoShows);
	}

	/**
	 * Clientes que SUMIRAM: já foram atendidos (concluído) neste estúdio, o último atendimento foi há mais de {@code days} dias e não há nenhum horário marcado de agora em
	 * diante. Os que ficaram há mais tempo sem voltar vêm por último; até {@value #MAX_LAPSED}. Só tutor ativo (conta anonimizada ou inativa não aparece).
	 */
	@Transactional(readOnly = true)
	public List<LapsedRow> lapsed(UUID ownerUserId, int days) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		if (days < MIN_LAPSED_DAYS || days > MAX_LAPSED_DAYS) {
			throw new InvalidConfigurationException("days", "entre " + MIN_LAPSED_DAYS + " e " + MAX_LAPSED_DAYS + " dias");
		}
		Instant now = clock.instant();
		java.time.ZoneId zone = java.time.ZoneId.of(provider.getTimeZone());
		Instant limit = now.minus(java.time.Duration.ofDays(days));
		List<Object[]> found = rows("select a.customer_id, max(a.starts_at) from appointments a where a.provider_id = ?1 and a.status = 'COMPLETED' "
				+ "and not exists (select 1 from appointments f where f.provider_id = a.provider_id and f.customer_id = a.customer_id "
				+ "and f.status in ('SCHEDULED', 'CHECKED_IN', 'IN_PROGRESS', 'READY') and f.starts_at >= ?3) "
				+ "group by 1 having max(a.starts_at) < ?2 order by 2 desc limit 200", provider.getId(), limit, now);
		Map<UUID, UserAccount> accounts = users.findAllById(found.stream().map(r -> (UUID) r[0]).toList()).stream()
				.filter(u -> u.getRole() == UserRole.CUSTOMER && u.isActive()).collect(Collectors.toMap(UserAccount::getId, Function.identity(), (a, b) -> a, HashMap::new));
		List<LapsedRow> result = new ArrayList<>();
		for (Object[] r : found) {
			UserAccount account = accounts.get((UUID) r[0]);
			if (account == null) {
				continue;
			}
			Instant last = toInstant(r[1]);
			result.add(new LapsedRow(account.getId(), account.getName(), account.getPhone(), last, calendarDaysSince(last, now, zone)));
			if (result.size() == MAX_LAPSED) {
				break;
			}
		}
		return result;
	}

	/**
	 * Dias de CALENDÁRIO do estúdio entre a última visita e agora, e não períodos de 24 h: a mesma visita não pode aparecer como "70 dias" à tarde e "69 dias" de manhã. É a
	 * mesma convenção do resto do resumo (o dia é o do fuso do estúdio, ADR 0031).
	 */
	static long calendarDaysSince(Instant last, Instant now, java.time.ZoneId zone) {
		return java.time.temporal.ChronoUnit.DAYS.between(last.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate());
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> rows(String sql, Object... params) {
		var query = entityManager.createNativeQuery(sql);
		for (int i = 0; i < params.length; i++) {
			query.setParameter(i + 1, params[i]);
		}
		return (List<Object[]>) (List<?>) query.getResultList().stream().map(r -> r instanceof Object[] ? r : new Object[] { r }).toList();
	}

	private static Instant toInstant(Object value) {
		if (value instanceof Instant i) {
			return i;
		}
		if (value instanceof java.time.OffsetDateTime o) {
			return o.toInstant();
		}
		if (value instanceof java.sql.Timestamp t) {
			return t.toInstant();
		}
		throw new IllegalStateException("tipo de data inesperado: " + value.getClass());
	}
}
