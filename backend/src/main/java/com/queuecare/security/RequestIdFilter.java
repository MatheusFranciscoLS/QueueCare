package com.queuecare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identificador de requisição (ADR 0029): cada chamada ganha um código curto que aparece em TODA linha de log dela (via MDC, {@code rid=...}) e volta no
 * cabeçalho {@code X-Request-Id}. Quando alguém diz "deu erro", o código da tela leva direto às linhas do log daquela chamada, sem procurar por horário.
 *
 * <p>Roda ANTES da cadeia de segurança (ordem mais alta), então cobre também 401, 403, 429 e erros do próprio filtro. Um código vindo do cliente só é aceito se for
 * curto e só tiver letras, dígitos, ponto, hífen e sublinhado: qualquer outra coisa (aspas, quebra de linha, tamanho grande) é descartada e substituída por um novo,
 * para ninguém injetar linhas falsas no log nem cabeçalhos na resposta. O MDC é sempre limpo no fim: a thread volta ao pool sem o código da chamada anterior.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String id = incoming != null && ACCEPTED.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		MDC.put(MDC_KEY, id);
		response.setHeader(HEADER, id);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}
}
