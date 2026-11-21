package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.ProviderExceptions.StudioNotFoundException;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Página pública do estúdio (ADR 0015), SEM login. Expõe só o que o estúdio publicou de propósito: nome, fuso e serviços ativos
 * (nome e duração). Nada de ids, e-mails, equipe, configurações nem agenda. Slug inválido, inexistente, não publicado ou de dono
 * inativo: a MESMA ausência (não revela quais endereços existem).
 */
@Service
public class PublicStudioService {

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;

	public PublicStudioService(ProviderRepository providers, ServiceOfferingRepository services) {
		this.providers = providers;
		this.services = services;
	}

	/** {@code durationMinutes} é a MENOR duração possível (o "a partir de"); {@code durationVaries} diz que ela muda com o porte ou o pelo do pet (ADR 0020). */
	public record PublicService(String name, int durationMinutes, boolean durationVaries) {
	}

	/** {@code cancellationDeadlineMinutes}: até quanto tempo antes o cliente cancela ou remarca de graça (ADR 0021): a regra vale também para quem ainda não agendou. */
	public record PublicStudio(String slug, String businessName, String timeZone, int cancellationDeadlineMinutes, List<PublicService> services) {
	}

	private static PublicService view(ServiceOffering s) {
		int base = s.getDurationMinutes();
		int shortest = Math.min(base, Math.min(s.getDurationMediumMinutes() == null ? base : s.getDurationMediumMinutes(), s.getDurationLargeMinutes() == null ? base : s.getDurationLargeMinutes()));
		boolean varies = shortest != base || (s.getDurationMediumMinutes() != null && s.getDurationMediumMinutes() != base) || (s.getDurationLargeMinutes() != null && s.getDurationLargeMinutes() != base)
				|| s.getCoatExtraMinutes() > 0;
		return new PublicService(s.getName(), shortest, varies);
	}

	@Transactional(readOnly = true)
	public PublicStudio find(String slug) {
		if (slug == null || slug.length() < 3 || slug.length() > 40 || !SLUG.matcher(slug).matches()) {
			throw new StudioNotFoundException();
		}
		Provider provider = providers.findPublishedBySlug(slug).orElseThrow(StudioNotFoundException::new);
		List<PublicService> offered = services.findByProviderIdAndActiveTrueOrderByNameAscIdAsc(provider.getId()).stream()
				.map(PublicStudioService::view).toList();
		return new PublicStudio(provider.getSlug(), provider.getBusinessName(), provider.getTimeZone(), provider.getCancellationDeadlineMinutes(), offered);
	}
}
