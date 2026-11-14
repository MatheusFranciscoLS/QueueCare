package com.queuecare.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limites de requisições (ADR 0003). Os padrões são conservadores para uso humano normal; os testes usam valores bem diferentes.
 *
 * @param enabled liga ou desliga SÓ os limites por minuto (login, cadastro e horários livres); o bloqueio por
 *        falhas de login tem chave própria ({@code loginLockoutEnabled}). Padrão: ligado
 * @param accountCreationsEnabled liga ou desliga SÓ o limite por hora de criação de contas (proteção contra enumeração de e-mails);
 *        independente de {@code enabled}. Padrão: ligado
 * @param loginLockoutEnabled liga ou desliga o bloqueio temporário por falhas de login (padrão: ligado; desligue só em teste)
 * @param loginPerMinute tentativas de login por minuto e por endereço
 * @param registerPerMinute cadastros por minuto e por endereço
 * @param slotsPerMinute consultas de horários livres por minuto e por usuário autenticado (ou por endereço, se sem login)
 * @param loginFailuresPerEmailAndIp tentativas de login SEM sucesso permitidas para o par e-mail + endereço dentro da janela;
 *        a seguinte recebe 429 (a contagem é reservada ANTES de conferir a senha, de forma atômica, então tentativas
 *        simultâneas não furam o limite). Login com sucesso zera a contagem
 * @param accountCreationsPerHour tentativas de criar contas para outras pessoas (ADMIN e prestador) por hora e por usuário autenticado,
 *        com ou sem sucesso: limita a descoberta de e-mails já cadastrados pelo "deu conflito"
 * @param passwordChangeAttempts tentativas de trocar a senha (senha atual conferida) por usuário dentro da janela abaixo; a seguinte
 *        recebe 429. Protege contra quem tem um token roubado e tenta adivinhar a senha atual. Sucesso zera a contagem
 * @param loginFailureWindowMinutes duração do bloqueio por falhas de login (a janela das tentativas)
 *
 * Todos os valores são VALIDADOS na inicialização (mínimo 1, máximo razoável): uma variável de ambiente digitada errada
 * (por exemplo, janela negativa, que faria a contagem recomeçar a cada tentativa) impede a aplicação de subir em vez de
 * desligar a proteção em silêncio.
 */
@Validated
@ConfigurationProperties(prefix = "queuecare.rate-limit")
public record RateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("true") boolean accountCreationsEnabled,
		@DefaultValue("true") boolean loginLockoutEnabled,
		@DefaultValue("20") @Min(1) @Max(MAX_PER_MINUTE) int loginPerMinute,
		@DefaultValue("10") @Min(1) @Max(MAX_PER_MINUTE) int registerPerMinute,
		@DefaultValue("120") @Min(1) @Max(MAX_PER_MINUTE) int slotsPerMinute,
		@DefaultValue("5") @Min(1) @Max(MAX_PER_MINUTE) int loginFailuresPerEmailAndIp,
		@DefaultValue("5") @Min(1) @Max(MAX_PER_MINUTE) int passwordChangeAttempts,
		@DefaultValue("15") @Min(1) @Max(MAX_WINDOW_MINUTES) int loginFailureWindowMinutes,
		@DefaultValue("30") @Min(1) @Max(MAX_PER_MINUTE) int accountCreationsPerHour) {

	/** Teto só para recusar valores absurdos (erro de digitação); os testes usam valores altos mas abaixo disto. */
	public static final int MAX_PER_MINUTE = 1_000_000;
	public static final int MAX_WINDOW_MINUTES = 24 * 60;
}
