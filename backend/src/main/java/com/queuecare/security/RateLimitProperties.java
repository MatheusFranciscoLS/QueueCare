package com.queuecare.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites de requisições (ADR 0003). Os padrões são conservadores para uso humano normal; os testes usam valores bem diferentes.
 *
 * @param enabled liga ou desliga o filtro (padrão: ligado)
 * @param loginPerMinute tentativas de login por minuto e por endereço
 * @param registerPerMinute cadastros por minuto e por endereço
 * @param slotsPerMinute consultas de horários livres por minuto e por usuário autenticado (ou por endereço, se sem login)
 * @param loginFailuresPerEmailAndIp falhas de login permitidas para o par e-mail + endereço antes do bloqueio temporário
 * @param loginFailureWindowMinutes duração do bloqueio por falhas de login (a janela das falhas)
 */
@ConfigurationProperties(prefix = "queuecare.rate-limit")
public record RateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("20") int loginPerMinute,
		@DefaultValue("10") int registerPerMinute,
		@DefaultValue("120") int slotsPerMinute,
		@DefaultValue("5") int loginFailuresPerEmailAndIp,
		@DefaultValue("15") int loginFailureWindowMinutes) {
}
