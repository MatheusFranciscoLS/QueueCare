package com.queuecare.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Trava os arquivos de publicação (ADR 0013): custo zero, sem segredo na imagem, usuário comum no contêiner e o perfil de produção
 * com as conexões certas (aplicação pelo pooler, migrations pela conexão direta). Quem mexer nisso sem querer quebra o CI.
 */
class DeploymentFilesTest {

	private static final Path ROOT = Path.of("..");

	private static String read(String relative) throws IOException {
		return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
	}

	private static PropertySource<?> yaml(String relative) throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(relative, new FileSystemResource(ROOT.resolve(relative)));
		return sources.get(0);
	}

	@Test
	void renderSoUsaPlanoGratuitoENenhumSegredoNoArquivo() throws IOException {
		String render = read("render.yaml");
		Matcher plans = Pattern.compile("(?m)^\\s*plan:\\s*(\\S+)").matcher(render);
		int found = 0;
		while (plans.find()) {
			assertEquals("free", plans.group(1), "só plano gratuito (nenhum meio de pagamento)");
			found++;
		}
		assertTrue(found >= 1, "o serviço precisa declarar plan: free");
		// todo valor secreto é informado no painel (sync: false) e nunca escrito aqui
		for (String secret : List.of("DB_URL", "DB_DIRECT_URL", "DB_USER", "DB_PASSWORD", "JWT_SECRET", "CORS_ALLOWED_ORIGINS")) {
			assertTrue(Pattern.compile("key:\\s*" + secret + "\\b[^\\n]*\\n\\s*sync:\\s*false").matcher(render).find(), secret + " deve ser sync: false");
		}
		assertFalse(Pattern.compile("(?m)^\\s*value:").matcher(render).find(), "nenhum valor literal no render.yaml");
		assertTrue(Pattern.compile("(?m)^\\s*healthCheckPath:\\s*/actuator/health/liveness(\\s|$)").matcher(render).find(), "o health check do Render é a VIVACIDADE (não depende do banco)");
		assertTrue(render.contains("autoDeployTrigger: \"off\""), "publicar só quando o dono decidir");
	}

	@Test
	void dockerfileRodaComoUsuarioComumSemSegredosENoPerfilDeProducao() throws IOException {
		String docker = read("backend/Dockerfile");
		assertTrue(Pattern.compile("(?m)^USER\\s+queuecare\\s*$").matcher(docker).find(), "não roda como root");
		assertTrue(docker.contains("SPRING_PROFILES_ACTIVE=prod"));
		assertFalse(Pattern.compile("(?i)(JWT_SECRET|DB_PASSWORD|ADMIN_PASSWORD|DB_USER)\\s*=").matcher(docker).find(), "nenhum segredo na imagem");
		assertTrue(docker.contains("-Xmx256m") && docker.contains("-XX:TieredStopAtLevel=1"), "memória e JIT para 512 MB e 0,1 CPU (medidos)");
		assertTrue(docker.contains("-XX:+ExitOnOutOfMemoryError"), "falta de memória reinicia em vez de ficar travado");
		long pinned = Pattern.compile("(?m)^FROM\\s+\\S+@sha256:[0-9a-f]{64}").matcher(docker).results().count();
		assertEquals(2, pinned, "as duas imagens base são fixadas por digest (reprodutível)");
		assertFalse(read("backend/.dockerignore").isBlank());
		assertTrue(read("backend/.dockerignore").contains(".env"), "arquivos .env nunca entram no contexto da imagem");
	}

	@Test
	void ciConfereOUidExatoDoContainerEFalhaSeOContainerNaoRodar() throws IOException {
		String ci = read(".github/workflows/ci.yml");
		assertTrue(ci.contains("uid=\"$(docker run --rm --entrypoint id queuecare-api:ci -u)\""), "captura o UID numa variável (falha do docker run derruba o job)");
		assertTrue(ci.contains("test \"$uid\" = \"10001\""), "exige o UID exato, nunca só 'diferente de 0'");
	}

	@Test
	void perfilDeProducaoUsaPoolPequenoPortaDaPlataformaEMigrationsPelaConexaoDireta() throws IOException {
		PropertySource<?> prod = yaml("backend/src/main/resources/application-prod.yml");
		assertEquals("${PORT:8080}", prod.getProperty("server.port"));
		assertEquals("${DB_POOL_MAX:3}", prod.getProperty("spring.datasource.hikari.maximum-pool-size"));
		assertEquals(0, prod.getProperty("spring.datasource.hikari.minimum-idle"));
		assertEquals("${DB_DIRECT_URL}", prod.getProperty("spring.flyway.url"), "sem a URL direta a aplicação não sobe (migrations nunca pelo pooler)");
		assertEquals("health", prod.getProperty("management.endpoints.web.exposure.include"), "só o health é exposto");
		for (String key : List.of("spring.flyway.user", "spring.flyway.password")) {
			assertTrue(String.valueOf(prod.getProperty(key)).startsWith("${DB_DIRECT_"), key);
		}
	}

	@Test
	void configuracaoBaseNaoLigaNadaSensivelPorPadrao() throws IOException {
		PropertySource<?> base = yaml("backend/src/main/resources/application.yml");
		assertEquals(true, base.getProperty("management.endpoint.health.probes.enabled"), "vivacidade e prontidão separadas");
		assertEquals("readinessState,db", base.getProperty("management.endpoint.health.group.readiness.include"));
		assertEquals("${OPENAPI_ENABLED:false}", base.getProperty("springdoc.api-docs.enabled"));
		assertEquals("validate", base.getProperty("spring.jpa.hibernate.ddl-auto"));
		assertEquals("${JWT_SECRET}", base.getProperty("queuecare.jwt.secret"), "sem valor padrão para o segredo do JWT");
		assertEquals("${DB_PASSWORD}", base.getProperty("spring.datasource.password"), "sem senha padrão para o banco");
	}
}
