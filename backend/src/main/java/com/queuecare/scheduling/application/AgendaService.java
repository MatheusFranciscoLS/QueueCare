package com.queuecare.scheduling.application;

import com.queuecare.pets.PetSize;
import com.queuecare.pets.PetSpecies;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AppointmentStatus;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agenda do dia do prestador, para o PROVIDER dono e para o ATTENDANT vinculado (ADR 0004). O prestador vem SEMPRE de
 * {@link StaffAccess}; não existe parâmetro de prestador. A data é a data LOCAL do prestador: o dia é o intervalo de
 * instantes [início do dia, início do dia seguinte) no fuso IANA dele (correto em dias de horário de verão).
 * Mostra só agendamentos NÃO cancelados e, do cliente, apenas id e nome (sem e-mail nem outros dados pessoais).
 */
@Service
public class AgendaService {

	private final UserAccountRepository users;
	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final AppointmentRepository appointments;
	private final StaffAccess staffAccess;
	private final Clock clock;

	public AgendaService(UserAccountRepository users, ProviderRepository providers, ServiceOfferingRepository services,
			AppointmentRepository appointments, StaffAccess staffAccess, Clock clock) {
		this.clock = clock;
		this.users = users;
		this.providers = providers;
		this.services = services;
		this.appointments = appointments;
		this.staffAccess = staffAccess;
	}

	/**
	 * O pet do agendamento, lido da CÓPIA gravada nele (nunca do cadastro): só quem já tem acesso ao agendamento chega aqui, porque as
	 * consultas da agenda e da fila filtram pelo prestador em que a pessoa opera. {@code breed} e {@code notes} são nulos na visão da semana
	 * (várias linhas de uma vez); aparecem na agenda do dia e na fila, onde o atendimento acontece (ADR 0016, item 4).
	 */
	public record PetInfo(String name, PetSpecies species, String breed, PetSize size, String notes) {
		/** Nulo se o agendamento não tem pet (antigo ou feito sem escolher um). */
		static PetInfo of(Appointment a, boolean withDetails) {
			if (a.getPetId() == null) {
				return null;
			}
			return new PetInfo(a.getPetName(), a.getPetSpecies(), withDetails ? a.getPetBreed() : null, a.getPetSize(), withDetails ? a.getPetNotes() : null);
		}
	}

	/** Um agendamento da agenda. */
	public record AgendaItem(UUID id, Instant startsAt, Instant endsAt, AppointmentStatus status, UUID serviceId, String serviceName,
			UUID customerId, String customerName, PetInfo pet) {
	}

	/** Uma pessoa na fila: posição (1 = a primeira a chegar), quem é, o que vai fazer e quando chegou. */
	public record QueueItem(int position, UUID id, Instant startsAt, Instant endsAt, UUID serviceId, String serviceName, UUID customerId, String customerName,
			Instant checkedInAt, PetInfo pet) {
	}

	/** Onde a pessoa trabalha: o negócio e o fuso em que os horários da agenda valem (o atendente não tem acesso a /api/provider). */
	public record Workplace(UUID providerId, String businessName, String timeZone) {
	}

	@Transactional(readOnly = true)
	public Workplace workplace(UUID userId) {
		Provider provider = resolveProvider(userId);
		return new Workplace(provider.getId(), provider.getBusinessName(), provider.getTimeZone());
	}

	/** O prestador em que o usuário opera, com as MESMAS regras da agenda (perfil, vínculo e dono ativo); nunca vem da requisição. */
	private Provider resolveProvider(UUID userId) {
		UserAccount actor = users.findById(userId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
		if (actor.getRole() != UserRole.PROVIDER && actor.getRole() != UserRole.ATTENDANT) {
			throw new AccessDeniedException("perfil sem acesso à agenda");
		}
		UUID providerId = staffAccess.providerIdOf(actor).orElseThrow(() -> actor.getRole() == UserRole.PROVIDER
				? new ProviderNotConfiguredException()
				: new AccessDeniedException("atendente sem vínculo com um prestador"));
		return providers.findById(providerId).orElseThrow(ProviderNotConfiguredException::new);
	}

	@Transactional(readOnly = true)
	public List<AgendaItem> day(UUID userId, LocalDate date) {
		return range(userId, date, 1, true);
	}

	/**
	 * Semana do prestador: 7 dias CIVIS a partir de {@code start} (a data LOCAL do prestador). É o intervalo [início do primeiro dia,
	 * início do oitavo dia) no fuso IANA dele, então cobre 167 ou 169 horas em semanas com horário de verão. Mesmas regras da agenda do dia
	 * (sem cancelados, só id e nome do cliente, prestador sempre vindo do usuário autenticado). A API devolve instantes UTC; quem agrupa por
	 * dia é a tela, no fuso do negócio.
	 */
	@Transactional(readOnly = true)
	public List<AgendaItem> week(UUID userId, LocalDate start) {
		return range(userId, start, WEEK_DAYS, false);
	}

	static final int WEEK_DAYS = 7;

	/** Datas aceitas na agenda: fora disto o cálculo do intervalo poderia estourar (resposta 400 em vez de 500). */
	static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
	static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

	private List<AgendaItem> range(UUID userId, LocalDate firstDay, int days, boolean petDetails) {
		if (firstDay.isBefore(MIN_DATE) || firstDay.isAfter(MAX_DATE)) {
			throw new ProviderExceptions.InvalidConfigurationException("date", "entre " + MIN_DATE + " e " + MAX_DATE);
		}
		Provider provider = resolveProvider(userId);
		UUID providerId = provider.getId();

		ZoneId zone = ZoneId.of(provider.getTimeZone());
		Instant rangeStart = firstDay.atStartOfDay(zone).toInstant();
		Instant rangeEnd = firstDay.plusDays(days).atStartOfDay(zone).toInstant();
		List<Appointment> found = appointments.findDayAgenda(providerId, rangeStart, rangeEnd);

		Map<UUID, String> serviceNames = services.findAllById(found.stream().map(Appointment::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, ServiceOffering::getName));
		Map<UUID, String> customerNames = users.findAllById(found.stream().map(Appointment::getCustomerId).distinct().toList()).stream()
				.collect(Collectors.toMap(UserAccount::getId, UserAccount::getName, (a, b) -> a, java.util.HashMap::new));
		return found.stream().map(a -> new AgendaItem(a.getId(), a.getStartsAt(), a.getEndsAt(), a.getStatus(), a.getServiceId(),
				serviceNames.get(a.getServiceId()), a.getCustomerId(), customerNames.get(a.getCustomerId()), PetInfo.of(a, petDetails))).toList();
	}


	/**
	 * Fila de HOJE (dia do prestador, no fuso dele): quem já chegou e ainda não foi atendido, por ordem de chegada. A posição é a
	 * ordem na lista; não existe reordenação manual. O prestador vem SEMPRE do usuário autenticado.
	 */
	@Transactional(readOnly = true)
	public List<QueueItem> queue(UUID userId) {
		Provider provider = resolveProvider(userId);
		ZoneId zone = ZoneId.of(provider.getTimeZone());
		LocalDate today = clock.instant().atZone(zone).toLocalDate();
		List<AppointmentRepository.QueueRow> rows = appointments.findQueue(provider.getId(), today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant());

		Map<UUID, String> serviceNames = services.findAllById(rows.stream().map(r -> r.appointment().getServiceId()).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, ServiceOffering::getName));
		Map<UUID, String> customerNames = users.findAllById(rows.stream().map(r -> r.appointment().getCustomerId()).distinct().toList()).stream()
				.collect(Collectors.toMap(UserAccount::getId, UserAccount::getName, (a, b) -> a, java.util.HashMap::new));
		List<QueueItem> queue = new java.util.ArrayList<>();
		for (AppointmentRepository.QueueRow row : rows) {
			Appointment a = row.appointment();
			queue.add(new QueueItem(queue.size() + 1, a.getId(), a.getStartsAt(), a.getEndsAt(), a.getServiceId(), serviceNames.get(a.getServiceId()), a.getCustomerId(),
					customerNames.get(a.getCustomerId()), row.checkedInAt(), PetInfo.of(a, true)));
		}
		return queue;
	}
}
