package com.queuecare.security;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Segurança da API (ADR 0003): stateless, sem sessão e sem cookie de autenticação (por isso CSRF desabilitado),
 * tudo exige token Bearer, exceto cadastro, login e a saúde da aplicação. 401 = não autenticado; 403 = autenticado sem permissão.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ActiveUserJwtConverter jwtConverter, RateLimiter rateLimiter,
			RateLimitProperties rateLimitProperties) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/api/auth/**").permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.bearerTokenResolver(new SingleAuthorizationHeaderResolver())
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
		if (rateLimitProperties.enabled()) {
			// Depois da leitura do token (conhece o usuário) e antes da autorização das rotas.
			http.addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitProperties), BearerTokenAuthenticationFilter.class);
		}
		return http.build();
	}

	/** BCrypt com custo calibrável (padrão 12). Testes usam custo baixo só para ficarem rápidos. */
	@Bean
	PasswordEncoder passwordEncoder(@Value("${queuecare.security.bcrypt-strength:12}") int strength) {
		return new BCryptPasswordEncoder(strength);
	}

	/** CORS restrito às origens configuradas (padrão: o frontend local do Vite). */
	@Bean
	CorsConfigurationSource corsConfigurationSource(@Value("${queuecare.cors.allowed-origins:http://localhost:5173}") List<String> origins) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(origins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}
