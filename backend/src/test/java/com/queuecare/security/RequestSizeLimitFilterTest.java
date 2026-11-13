package com.queuecare.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Limite de tamanho do corpo: 413 quando o tamanho declarado já passa; leitura cortada quando não há tamanho confiável. */
class RequestSizeLimitFilterTest {

	private static final byte[] BIG = "x".repeat(2_000).getBytes(StandardCharsets.UTF_8);

	/** Requisição que declara o tamanho (-1 = não declara, como no envio em pedaços). */
	private static MockHttpServletRequest request(byte[] body, long declaredLength) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login") {
			@Override
			public long getContentLengthLong() {
				return declaredLength;
			}
		};
		request.setContent(body);
		return request;
	}

	@Test
	void tamanhoDeclaradoMaiorQueOLimiteVira413SemChamarOResto() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean reached = new AtomicBoolean();
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				reached.set(true);
			}
		};

		new RequestSizeLimitFilter(1_000).doFilter(request(BIG, BIG.length), response, chain);

		assertEquals(413, response.getStatus());
		assertFalse(reached.get(), "nada foi lido nem processado");
	}

	@Test
	void corpoDentroDoLimiteChegaIntacto() throws Exception {
		byte[] small = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
		byte[][] received = new byte[1][];
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
				received[0] = req.getInputStream().readAllBytes();
			}
		};

		new RequestSizeLimitFilter(1_000).doFilter(request(small, small.length), new MockHttpServletResponse(), chain);

		assertEquals("{\"ok\":true}", new String(received[0], StandardCharsets.UTF_8));
	}

	@Test
	void semTamanhoDeclaradoALeituraEhCortadaAoPassarDoLimite() throws Exception {
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
				assertThrows(IOException.class, () -> req.getInputStream().readAllBytes());
			}
		};

		new RequestSizeLimitFilter(1_000).doFilter(request(BIG, -1), new MockHttpServletResponse(), chain);
	}

	@Test
	void leitorDeCaracteresTambemEhLimitado() throws Exception {
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
				assertThrows(IOException.class, () -> {
					char[] buffer = new char[4_096];
					while (req.getReader().read(buffer) >= 0) {
						// consome tudo
					}
				});
			}
		};

		new RequestSizeLimitFilter(1_000).doFilter(request(BIG, -1), new MockHttpServletResponse(), chain);
	}

	@Test
	void limiteInvalidoImpedeACriacao() {
		assertThrows(IllegalArgumentException.class, () -> new RequestSizeLimitFilter(0));
		assertTrue(new RequestSizeLimitFilter(1) != null);
	}
}
