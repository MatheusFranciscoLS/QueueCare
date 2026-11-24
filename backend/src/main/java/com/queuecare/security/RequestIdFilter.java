package com.queuecare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identificador de requisição (ADR 0029): cada chamada ganha um código curto que aparece em TODA linha de log dela (via MDC, {@code rid=...}) e volta no
 * cabeçalho {@code X-Request-Id}. Quando alguém diz "deu erro", o código da tela leva direto às linhas do log daquela chamada, sem procurar por horário.
 *
 * <p>Roda ANTES da cadeia de segurança (ordem mais alta), então cobre também 401, 403, 429 e erros do próprio filtro. Um código vindo do cliente só é aceito se for
 * curto e só tiver letras, dígitos, ponto, hífen e sublinhado: qualquer outra coisa (aspas, quebra de linha, tamanho grande) é descartada e substituída por um novo,
 * para ninguém injetar linhas falsas no log nem cabeçalhos na resposta. O MDC é sempre limpo no fim: a thread volta ao pool sem o código da chamada anterior.
 *
 * <p><b>Log de acesso</b> (ADR 0036): ao fim de cada chamada, UMA linha com método, rota, status e duração em milissegundos, junto do código da requisição (que já vai no MDC).
 * A rota é o PADRÃO da rota ({@code /api/appointments/{id}/cancel}), nunca o caminho real: identificadores e dados de pessoas não entram no log. Nada de consulta, cabeçalhos
 * (inclusive Authorization) nem corpo. A duração usa relógio monotônico. As verificações de saúde ficam de fora (o Render e o monitor chamam o tempo todo).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	/** Nome do logger do log de acesso (para ligar, desligar ou separar o nível por configuração). */
	public static final String ACCESS_LOGGER = "queuecare.access";
	private static final Logger ACCESS = LoggerFactory.getLogger(ACCESS_LOGGER);
	private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String id = incoming != null && ACCEPTED.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		MDC.put(MDC_KEY, id);
		response.setHeader(HEADER, id);
		long started = System.nanoTime();
		try {
			chain.doFilter(request, response);
		} finally {
			logAccess(request, response, (System.nanoTime() - started) / 1_000_000);
			MDC.remove(MDC_KEY);
		}
	}

	private static void logAccess(HttpServletRequest request, HttpServletResponse response, long millis) {
		String uri = request.getRequestURI();
		if (uri != null && uri.startsWith("/actuator/")) {
			return;
		}
		// O padrão da rota só existe quando o Spring MVC chegou a escolher um controlador; recusas dos filtros (401, 403, 429) não têm rota, e o caminho real não é registrado.
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		String route = pattern == null ? "(sem rota)" : pattern.toString();
		int status = response.getStatus();
		if (status >= 500) {
			ACCESS.warn("{} {} -> {} em {} ms", request.getMethod(), route, status, millis);
		} else {
			ACCESS.info("{} {} -> {} em {} ms", request.getMethod(), route, status, millis);
		}
	}
}
