package com.queuecare.scheduling.application;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Reconhece, numa cadeia de exceções (Spring, Hibernate, JDBC), o erro do PostgreSQL que significa "perdi a disputa
 * pelo horário" (ADR 0002 e teste T3):
 * <ul>
 *   <li>{@code 23P01} (exclusion_violation) da restrição {@code appointments_no_overlap};</li>
 *   <li>{@code 40P01} (deadlock_detected): duas transações disputando o mesmo intervalo se esperam e o banco desfaz uma.</li>
 * </ul>
 * Qualquer outro erro (chave estrangeira, campo obrigatório, conexão, outra restrição) NÃO é conflito de horário.
 */
public final class SchedulingConflictDetector {

	static final String EXCLUSION_VIOLATION = "23P01";
	static final String DEADLOCK_DETECTED = "40P01";
	static final String OVERLAP_CONSTRAINT = "appointments_no_overlap";

	private SchedulingConflictDetector() {
	}

	public static boolean isConflict(Throwable error) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable t = error; t != null && visited.add(t); t = t.getCause()) {
			if (t instanceof SQLException sql && isConflict(sql)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isConflict(SQLException first) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (SQLException e = first; e != null && visited.add(e); e = e.getNextException()) {
			String state = e.getSQLState();
			if (DEADLOCK_DETECTED.equals(state)) {
				return true;
			}
			if (EXCLUSION_VIOLATION.equals(state) && e.getMessage() != null && e.getMessage().contains(OVERLAP_CONSTRAINT)) {
				return true;
			}
		}
		return false;
	}
}
