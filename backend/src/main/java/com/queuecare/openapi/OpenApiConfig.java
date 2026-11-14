package com.queuecare.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.queuecare.security.RateLimitedRoutes;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Contrato OpenAPI da API (ADR 0012). A documentação é GERADA do código: os perfis de cada operação vêm da própria anotação
 * {@code @PreAuthorize} (a mesma regra que a API aplica), então a descrição não pode divergir da autorização real. O endpoint
 * {@code /v3/api-docs} fica DESLIGADO por padrão (liga com {@code OPENAPI_ENABLED=true}); o contrato publicado é o arquivo
 * {@code docs/openapi.json}, conferido por um teste.
 */
@Configuration
class OpenApiConfig {

	static final String BEARER = "bearerAuth";
	private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
	/** As ÚNICAS expressões de perfil entendidas: hasRole('A') e hasAnyRole('A', 'B'). Qualquer outra faz a geração FALHAR (nunca publica descrição enganosa). */
	private static final Pattern SUPPORTED = Pattern.compile("^\\s*(hasRole\\('[A-Z_]+'\\)|hasAnyRole\\(\\s*'[A-Z_]+'(\\s*,\\s*'[A-Z_]+')*\\s*\\))\\s*$");

	@Bean
	OpenAPI queueCareOpenApi() {
		Schema<?> problem = new ObjectSchema().description("Erro no formato application/problem+json. O campo reason é um código estável.")
				.addProperty("type", new StringSchema().format("uri").description("Sempre about:blank nesta API."))
				.addProperty("title", new StringSchema().description("Texto padrão do status HTTP."))
				.addProperty("status", new IntegerSchema().format("int32"))
				.addProperty("detail", new StringSchema())
				.addProperty("instance", new StringSchema().format("uri").description("Presente só nos erros que passam pelo controlador."))
				.addProperty("reason", new StringSchema().description("Código estável do motivo (ex.: SLOT_TAKEN, TOO_LATE)."))
				.addProperty("errors", new ObjectSchema().additionalProperties(new StringSchema()).description("Erros por campo (400)."));
		return new OpenAPI()
				.info(new Info().title("QueueCare API").version("1.0")
						.description("Agendamento e fila de atendimento para pequenos prestadores. Perfis: ADMIN, PROVIDER, ATTENDANT, CUSTOMER. "
								+ "Horários em UTC; o prestador tem fuso IANA. Erros em application/problem+json com o campo reason."))
				.components(new Components().addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
						.addSchemas("Problem", problem))
				.addSecurityItem(new SecurityRequirement().addList(BEARER));
	}

	/** Descreve quem pode chamar cada operação a partir da regra REAL (@PreAuthorize) e acrescenta as respostas de erro comuns. */
	@Bean
	OperationCustomizer rolesAndErrors() {
		return (Operation operation, org.springframework.web.method.HandlerMethod handler) -> {
			PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
			if (rule == null) {
				rule = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
			}
			// Só o AuthController tem rotas públicas (cadastro e login); a troca de senha dele exige token.
			boolean publicRoute = rule == null && handler.getBeanType().getSimpleName().equals("AuthController") && !handler.getMethod().getName().equals("changePassword");
			String who;
			if (publicRoute) {
				who = "Público (sem token).";
				operation.setSecurity(new ArrayList<>());
			} else if (rule == null) {
				who = "Qualquer usuário autenticado.";
			} else {
				who = "Perfis: " + String.join(", ", parseRoles(rule.value())) + ".";
			}
			String current = operation.getDescription();
			operation.setDescription(current == null || current.isBlank() ? who : current + "\n\n" + who);
			if (!publicRoute) {
				// Fiel ao que a API entrega: 401 e 403 por perfil vêm da cadeia de segurança do Spring, com CORPO VAZIO; só a troca de senha obrigatória tem corpo.
				operation.getResponses().addApiResponse("401", new ApiResponse().description("Sem token ou token inválido. Corpo vazio; o cabeçalho WWW-Authenticate: Bearer indica o esquema."));
				operation.getResponses().addApiResponse("403", problem("O perfil não tem permissão (corpo vazio) ou a troca de senha é obrigatória (application/problem+json com reason PASSWORD_CHANGE_REQUIRED)."));
			}
			return operation;
		};
	}

	/** Perfis de uma expressão @PreAuthorize; só hasRole e hasAnyRole são aceitos. */
	static List<String> parseRoles(String expression) {
		if (!SUPPORTED.matcher(expression).matches()) {
			throw new IllegalStateException("Expressão @PreAuthorize não suportada pelo gerador do OpenAPI: " + expression
					+ " (use hasRole/hasAnyRole ou ensine o gerador a descrevê-la)");
		}
		List<String> roles = new ArrayList<>();
		Matcher m = QUOTED.matcher(expression);
		while (m.find()) {
			roles.add(m.group(1));
		}
		return roles;
	}

	/** O 429 só existe onde a API realmente limita: a lista vem da MESMA fonte que o RateLimitFilter usa (RateLimitedRoutes). */
	@Bean
	OpenApiCustomizer rateLimitResponses() {
		return openApi -> openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
			if (RateLimitedRoutes.covers(method.name(), path)) {
				operation.getResponses().addApiResponse("429", problem("Muitos pedidos (application/problem+json sem detalhes).")
						.addHeaderObject("Retry-After", new Header().description("Segundos até poder tentar de novo.").schema(new IntegerSchema())));
			}
		}));
	}

	private static ApiResponse problem(String description) {
		return new ApiResponse().description(description)
				.content(new Content().addMediaType("application/problem+json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem"))));
	}

}
