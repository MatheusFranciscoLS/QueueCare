package com.queuecare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Teste de arranque: garante que o contexto do Spring sobe.
 * Usa o perfil "test" (H2 em memória), então não valida banco real.
 */
@SpringBootTest
@ActiveProfiles("test")
class QueuecareApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
