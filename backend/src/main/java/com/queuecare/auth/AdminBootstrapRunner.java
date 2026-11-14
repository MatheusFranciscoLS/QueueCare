package com.queuecare.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Comando de uma vez só que cria o primeiro ADMIN. SÓ existe quando {@code queuecare.bootstrap-admin.enabled=true}
 * (padrão: desligado) e, ao terminar, ENCERRA a aplicação (código 0 = criado ou já existia; 1 = erro), sem ficar servindo.
 * E-mail e senha vêm de {@code ADMIN_EMAIL} e {@code ADMIN_PASSWORD} (variáveis de ambiente, nunca arquivo versionado) e
 * NUNCA são escritos em log. Como executar: ver ADR 0005 e README.
 */
@Component
@ConditionalOnProperty(name = "queuecare.bootstrap-admin.enabled", havingValue = "true")
class AdminBootstrapRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

	/** Encerra o processo; em teste, é substituído por um registrador. */
	interface ProcessExit {
		void exit(int code);
	}

	private final AdminBootstrapService service;
	private final String email;
	private final String password;
	private final ProcessExit processExit;

	/** Em produção encerra o processo de verdade; um teste pode fornecer o seu {@link ProcessExit} como bean. */
	@org.springframework.beans.factory.annotation.Autowired
	AdminBootstrapRunner(AdminBootstrapService service, ConfigurableApplicationContext context, ObjectProvider<ProcessExit> exitProvider,
			@Value("${ADMIN_EMAIL:}") String email, @Value("${ADMIN_PASSWORD:}") String password) {
		this(service, email, password, exitProvider.getIfAvailable(() -> code -> System.exit(SpringApplication.exit(context, () -> code))));
	}

	AdminBootstrapRunner(AdminBootstrapService service, String email, String password, ProcessExit processExit) {
		this.service = service;
		this.email = email;
		this.password = password;
		this.processExit = processExit;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			AdminBootstrapService.Result result = service.bootstrap(email, password);
			log.info(result == AdminBootstrapService.Result.CREATED ? "bootstrap: ADMIN criado" : "bootstrap: já existe um ADMIN; nada foi alterado");
			code = 0;
		} catch (AdminBootstrapService.BootstrapException e) {
			log.error("bootstrap recusado: {}", e.getMessage()); // a mensagem nunca contém o e-mail nem a senha
			code = 1;
		} catch (RuntimeException e) {
			log.error("bootstrap falhou: {}", e.getClass().getSimpleName()); // só o tipo: a causa pode ecoar dados
			code = 1;
		}
		processExit.exit(code);
	}
}
