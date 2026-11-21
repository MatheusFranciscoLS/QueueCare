package com.queuecare.scheduling.application;

import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AppointmentStatus;
import com.queuecare.scheduling.persistence.DepositRule;
import com.queuecare.scheduling.persistence.Provider;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Quando o estúdio pede sinal por Pix (ADR 0023): decidido NO SERVIDOR, na reserva. Regra NEVER nunca pede; NEW_CUSTOMERS pede de quem ainda não teve
 * atendimento CONCLUÍDO neste estúdio; NEW_OR_REPEAT_NO_SHOW pede também de quem já faltou 2 ou mais vezes aqui. A contagem de faltas nunca sai daqui:
 * o tutor só fica sabendo que o sinal é pedido.
 */
@Component
public class DepositPolicy {

	/** Faltas neste estúdio a partir das quais NEW_OR_REPEAT_NO_SHOW pede sinal. */
	static final int NO_SHOWS_THAT_REQUIRE_DEPOSIT = 2;

	private final AppointmentRepository appointments;

	public DepositPolicy(AppointmentRepository appointments) {
		this.appointments = appointments;
	}

	/** O sinal combinado (valor em centavos) que este tutor deve neste estúdio, ou nulo se não deve nenhum. */
	public Integer requiredCents(Provider provider, UUID customerId) {
		if (provider.getDepositRule() == DepositRule.NEVER || provider.getDepositCents() == null || provider.getPixKey() == null) {
			return null;
		}
		boolean isNew = appointments.countByProviderIdAndCustomerIdAndStatus(provider.getId(), customerId, AppointmentStatus.COMPLETED) == 0;
		boolean repeatNoShow = provider.getDepositRule() == DepositRule.NEW_OR_REPEAT_NO_SHOW
				&& appointments.countByProviderIdAndCustomerIdAndStatus(provider.getId(), customerId, AppointmentStatus.NO_SHOW) >= NO_SHOWS_THAT_REQUIRE_DEPOSIT;
		return isNew || repeatNoShow ? provider.getDepositCents() : null;
	}
}
