package com.queuecare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de tamanho do corpo das requisições (padrão 64 KB; todos os corpos desta API são JSON pequenos). Sem isso o Spring
 * leria um JSON de qualquer tamanho para a memória, e campos como e-mail e senha entrariam sem limite em chaves do limitador.
 * Se o cabeçalho {@code Content-Length} já declara mais que o limite: 413, sem ler nada. Se o corpo não declara tamanho
 * (chunked) ou mente, a leitura é CORTADA ao passar do limite (a requisição falha com 400). O ideal é ter o mesmo limite
 * também no proxy/balanceador da borda.
 */
final class RequestSizeLimitFilter extends OncePerRequestFilter {

	private final long maxBytes;

	RequestSizeLimitFilter(long maxBytes) {
		if (maxBytes < 1) {
			throw new IllegalArgumentException("queuecare.http.max-body-bytes deve ser positivo");
		}
		this.maxBytes = maxBytes;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
			response.setContentType("application/problem+json");
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,\"detail\":\"corpo da requisição grande demais\"}");
			return;
		}
		chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
	}

	/** Entrega no máximo {@code maxBytes} do corpo; ao passar disso, a leitura falha com {@link IOException}. */
	private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

		private final long maxBytes;
		private ServletInputStream stream;
		private BufferedReader reader;

		LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (reader != null) {
				throw new IllegalStateException("getReader() já foi chamado");
			}
			if (stream == null) {
				stream = new LimitedStream(super.getInputStream(), maxBytes);
			}
			return stream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			if (reader == null) {
				String encoding = getCharacterEncoding();
				Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
				reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
			}
			return reader;
		}
	}

	private static final class LimitedStream extends ServletInputStream {

		private final ServletInputStream delegate;
		private final long maxBytes;
		private long read;

		LimitedStream(ServletInputStream delegate, long maxBytes) {
			this.delegate = delegate;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			int b = delegate.read();
			if (b >= 0) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = delegate.read(buffer, offset, length);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) throws IOException {
			read += n;
			if (read > maxBytes) {
				throw new IOException("corpo da requisição passou do limite de " + maxBytes + " bytes");
			}
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			delegate.setReadListener(readListener);
		}
	}
}
