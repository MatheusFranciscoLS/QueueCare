package com.queuecare.auth;

import com.queuecare.auth.AuthExceptions.InvalidPhoneException;
import com.queuecare.auth.AuthExceptions.TooManyLoginAttemptsException;
import com.queuecare.pets.PetService;
import com.queuecare.pets.PetText;
import com.queuecare.scheduling.application.StaffAccess;
import com.queuecare.scheduling.application.StudioCustomerService;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.security.RateLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Importação de clientes de uma planilha (ADR 0034): o dono traz a lista que já tem no caderno ou no Excel e ela vira tutores cadastrados pelo balcão (ADR 0033), com a
 * mesma regra de segurança: contas ligadas ao estúdio, sem acesso ao app.
 *
 * <p>Cada linha é INDEPENDENTE (uma transação por linha): linha ruim é relatada e as outras entram. O que a importação NUNCA faz: duplicar um telefone que o estúdio já tem
 * (nem entre as linhas do próprio arquivo), adivinhar a espécie de um pet (sem espécie reconhecida o tutor entra e o pet não), nem repetir na resposta o que veio na planilha
 * (nome, telefone e e-mail são dados pessoais: o resultado diz a linha e o motivo, nunca o valor).
 */
@Service
public class CounterImport {

	public static final int MAX_ROWS = 200;
	private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
	static final int IMPORTS_PER_HOUR = 5;

	private final CounterCustomers counter;
	private final StaffAccess staffAccess;
	private final StudioCustomerService studioCustomers;
	private final PetService pets;
	private final RateLimiter limiter;

	public CounterImport(CounterCustomers counter, StaffAccess staffAccess, StudioCustomerService studioCustomers, PetService pets,
			@Qualifier(RateLimiter.LOGIN_LOCKOUT_LIMITER) RateLimiter limiter) {
		this.counter = counter;
		this.staffAccess = staffAccess;
		this.studioCustomers = studioCustomers;
		this.pets = pets;
		this.limiter = limiter;
	}

	/** Uma linha da planilha, já interpretada pela tela. {@code petSpecies}: DOG ou CAT; qualquer outra coisa conta como "não reconhecida". */
	public record Row(String name, String phone, String email, String petName, String petSpecies) {
	}

	public enum Status {
		/** O tutor foi cadastrado. */
		CREATED,
		/** Já existe tutor do estúdio com este telefone (ou a linha repete um telefone do próprio arquivo): nada foi criado. */
		DUPLICATE_PHONE,
		/** Nome ou telefone inválido: nada foi criado. */
		INVALID,
		/** O e-mail já tem cadastro no app: nada foi criado (o tutor agenda sozinho). */
		EMAIL_TAKEN
	}

	/** O resultado de uma linha ({@code row} começa em 1). {@code petCreated}/{@code petSkipped}: só quando a linha trazia um pet. */
	public record RowResult(int row, Status status, boolean petCreated, boolean petSkipped) {
	}

	public record Result(int created, int duplicates, int invalid, int emailTaken, int petsCreated, int petsSkipped, List<RowResult> rows) {
	}

	/** Importa as linhas para o estúdio de quem pede. O limite de importações por hora é do estúdio e conta ANTES de qualquer conferência. */
	public Result run(UUID staffUserId, List<Row> rows) {
		UUID providerId = staffAccess.providerIdOf(staffUserId).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
		Duration window = Duration.ofHours(1);
		String key = "counterimport|" + providerId;
		if (!limiter.tryAcquire(key, IMPORTS_PER_HOUR, window)) {
			throw new TooManyLoginAttemptsException(limiter.retryAfterSeconds(key, window));
		}
		if (rows.size() > MAX_ROWS) {
			throw new IllegalArgumentException("linhas demais");
		}
		Set<String> phones = new java.util.HashSet<>(studioCustomers.knownPhones(providerId));
		List<RowResult> results = new ArrayList<>(rows.size());
		int created = 0;
		int duplicates = 0;
		int invalid = 0;
		int emailTaken = 0;
		int petsCreated = 0;
		int petsSkipped = 0;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int number = i + 1;
			String phone;
			try {
				phone = PhoneNumber.normalize(row.phone());
			} catch (InvalidPhoneException e) {
				phone = null;
			}
			boolean emailOk = row.email() == null || row.email().isBlank() || (row.email().strip().length() <= 254 && EMAIL.matcher(row.email().strip()).matches());
			if (phone == null || !emailOk || row.name() == null || row.name().isBlank() || row.name().strip().length() > 120) {
				results.add(new RowResult(number, Status.INVALID, false, false));
				invalid++;
				continue;
			}
			if (phones.contains(phone)) {
				results.add(new RowResult(number, Status.DUPLICATE_PHONE, false, false));
				duplicates++;
				continue;
			}
			UserAccount account;
			try {
				account = counter.createUnlimited(providerId, staffUserId, row.name(), phone, row.email());
			} catch (AccountConflictException e) {
				results.add(new RowResult(number, Status.EMAIL_TAKEN, false, false));
				emailTaken++;
				continue;
			} catch (RuntimeException e) {
				results.add(new RowResult(number, Status.INVALID, false, false));
				invalid++;
				continue;
			}
			phones.add(phone);
			created++;
			boolean hasPet = row.petName() != null && !row.petName().isBlank();
			boolean petCreated = false;
			if (hasPet) {
				petCreated = createPet(account.getId(), row);
				if (petCreated) {
					petsCreated++;
				} else {
					petsSkipped++;
				}
			}
			results.add(new RowResult(number, Status.CREATED, petCreated, hasPet && !petCreated));
		}
		return new Result(created, duplicates, invalid, emailTaken, petsCreated, petsSkipped, results);
	}

	/** O pet só entra com espécie reconhecida (DOG ou CAT) e nome válido; qualquer problema só deixa o pet de fora, e o tutor continua cadastrado. */
	private boolean createPet(UUID customerId, Row row) {
		if (!"DOG".equals(row.petSpecies()) && !"CAT".equals(row.petSpecies())) {
			return false;
		}
		try {
			pets.create(customerId, new PetText.Input(row.petName(), row.petSpecies(), null, null, null));
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}
}
