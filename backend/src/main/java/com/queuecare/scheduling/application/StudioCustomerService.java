package com.queuecare.scheduling.application;

import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AppointmentRepository.CustomerStat;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Os clientes do estúdio (ADR 0025): quem já teve ao menos um agendamento nele, para a equipe achar um tutor no balcão por nome, telefone ou nome do pet.
 * O estúdio só enxerga tutores que ele atendeu (nunca o cadastro inteiro da plataforma) e só os dados que a agenda já mostra: nome, telefone, nomes dos pets
 * (a cópia gravada nos agendamentos dele), quantos atendimentos concluídos, quantas faltas e quando foi o agendamento mais recente. E-mail não aparece.
 */
@Service
public class StudioCustomerService {

	static final int MAX_RESULTS = 30;
	static final int MAX_QUERY = 80;

	private final AppointmentRepository appointments;
	private final UserAccountRepository users;
	private final StaffAccess staffAccess;

	public StudioCustomerService(AppointmentRepository appointments, UserAccountRepository users, StaffAccess staffAccess) {
		this.appointments = appointments;
		this.users = users;
		this.staffAccess = staffAccess;
	}

	public record CustomerView(UUID id, String name, String phone, long visits, long noShows, Instant lastAppointmentAt, List<String> pets) {
	}

	/** Busca por nome, telefone (só dígitos) ou nome de pet; sem texto, os mais recentes. Até {@value #MAX_RESULTS}, do agendamento mais recente para o mais antigo. */
	@Transactional(readOnly = true)
	public List<CustomerView> search(UUID staffUserId, String query) {
		UUID providerId = staffAccess.providerIdOf(staffUserId).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
		String q = query == null ? "" : query.trim();
		if (q.length() > MAX_QUERY) {
			q = q.substring(0, MAX_QUERY);
		}
		String textNeedle = fold(q);
		String digitNeedle = q.replaceAll("\\D", "");

		List<CustomerStat> stats = appointments.customerStats(providerId);
		Map<UUID, UserAccount> accounts = users.findAllById(stats.stream().map(CustomerStat::customerId).toList()).stream()
				.filter(u -> u.getRole() == UserRole.CUSTOMER && u.isActive()).collect(Collectors.toMap(UserAccount::getId, Function.identity()));
		Map<UUID, Set<String>> pets = new HashMap<>();
		for (Object[] row : appointments.petNamesByCustomer(providerId)) {
			pets.computeIfAbsent((UUID) row[0], k -> new TreeSet<>()).add((String) row[1]);
		}

		List<CustomerView> found = new ArrayList<>();
		for (CustomerStat stat : stats) {
			UserAccount account = accounts.get(stat.customerId());
			if (account == null) {
				continue;
			}
			Set<String> petNames = pets.getOrDefault(stat.customerId(), Set.of());
			if (!q.isEmpty() && !matches(account, petNames, textNeedle, digitNeedle)) {
				continue;
			}
			found.add(new CustomerView(account.getId(), account.getName(), account.getPhone(), stat.visits(), stat.noShows(), stat.lastAt(), List.copyOf(petNames)));
		}
		found.sort(Comparator.comparing(CustomerView::lastAppointmentAt, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(CustomerView::name, String.CASE_INSENSITIVE_ORDER));
		return found.size() > MAX_RESULTS ? List.copyOf(found.subList(0, MAX_RESULTS)) : found;
	}

	private static boolean matches(UserAccount account, Set<String> petNames, String textNeedle, String digitNeedle) {
		if (!textNeedle.isEmpty() && fold(account.getName()).contains(textNeedle)) {
			return true;
		}
		if (!textNeedle.isEmpty() && petNames.stream().anyMatch(p -> fold(p).contains(textNeedle))) {
			return true;
		}
		// Telefone só conta com 3 dígitos ou mais (um dígito solto casaria com quase todos).
		return digitNeedle.length() >= 3 && account.getPhone() != null && account.getPhone().contains(digitNeedle);
	}

	/** Minúsculas e sem acento: "joão" encontra "Joao" e "JOÃO". */
	static String fold(String text) {
		if (text == null) {
			return "";
		}
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
	}
}
