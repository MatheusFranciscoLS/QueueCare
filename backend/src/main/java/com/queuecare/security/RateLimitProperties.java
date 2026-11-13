package com.queuecare.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites de requisições (ADR 0003). Os padrões são conservadores para uso humano normal; os testes usam valores bem diferentes.
 *
 * @param enabled liga ou desliga SÓ o filtro de requisições por minuto (login, cadastro e horários livres); o bloqueio por
 *        falhas de login tem chave própria ({@code loginLockoutEnabled}). Padrão: ligado
 * @param loginLockoutEnabled liga ou desliga o bloqueio temporário por falhas de login (padrão: ligado; desligue só em teste)
 * @param loginPerMinute tentativas de login por minuto e por endereço
 * @param registerPerMinute cadastros por minuto e por endereço
 * @param slotsPerMinute consultas de horários livres por minuto e por usuário autenticado (ou por endereço, se sem login)
 * @param loginFailuresPerEmailAndIp tentativas de login SEM sucesso permitidas para o par e-mail + endereço dentro da janela;
 *        a seguinte recebe 429 (a contagem é reservada ANTES de conferir a senha, de forma atômica, então tentativas
 *        simultâneas não furam o limite). Login com sucesso zera a contagem
 * @param loginFailureWindowMinutes duração do bloqueio por falhas de login (a janela das tentativas)
 */
@ConfigurationProperties(prefix = "queuecare.rate-limit")
public record RateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("true") boolean loginLockoutEnabled,
		@DefaultValue("20") int loginPerMinute,
		@DefaultValue("10") int registerPerMinute,
		@DefaultValue("120") int slotsPerMinute,
		@DefaultValue("5") int loginFailuresPerEmailAndIp,
		@DefaultValue("15") int loginFailureWindowMinutes) {
}
