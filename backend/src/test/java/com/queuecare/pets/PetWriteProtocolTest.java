package com.queuecare.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guarda estrutural do ADR 0016 (item 5.2): toda operação que torna um pet ATIVO (criar e reativar) passa pelo MESMO protocolo
 * (bloquear o cliente, conferir limites, gravar), implementado em um único método de {@link PetService}. Este teste lê o código
 * principal e falha se surgir outro ponto que crie um {@code Pet} ou o reative, ou se outra classe passar a gravar pets: quem
 * precisar de um caminho novo tem de passar pelo serviço (e, portanto, pelo bloqueio).
 */
class PetWriteProtocolTest {

	private static final Path MAIN = Path.of("src", "main", "java");

	private static List<Path> sources() throws IOException {
		try (Stream<Path> files = Files.walk(MAIN)) {
			return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
	}

	private static long occurrences(String text, String needle) {
		long count = 0;
		for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}

	/** Arquivos com uma criação da classe, com o nome curto OU completo: {@code new Pet(} e {@code new com.x.Pet(}. */
	private static List<String> filesCreating(String className) throws IOException {
		java.util.regex.Pattern creation = java.util.regex.Pattern.compile("new\s+(?:[\w.]+\.)?" + className + "\s*\(");
		return sources().stream().filter(p -> {
			try {
				return creation.matcher(Files.readString(p)).find();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
	}

	private static List<String> filesContaining(String needle) throws IOException {
		return sources().stream().filter(p -> {
			try {
				return Files.readString(p).contains(needle);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
	}

	@Test
	void soOServicoCriaPetEReativaPetEUmaVezCadaUm() throws IOException {
		assertEquals(List.of("PetService.java"), filesCreating("Pet"), "só o serviço cria Pet");
		// reactivate( aparece na entidade (definição), no serviço (o protocolo) e no controlador (que só delega ao serviço)
		assertEquals(List.of("Pet.java", "PetController.java", "PetService.java"), filesContaining("reactivate("), "nenhum outro arquivo reativa Pet");
		assertEquals(0, occurrences(Files.readString(MAIN.resolve("com/queuecare/pets/PetController.java")), "pet.reactivate("), "o controlador não reativa direto");
		String service = Files.readString(MAIN.resolve("com/queuecare/pets/PetService.java"));
		assertEquals(1, occurrences(service, "new Pet("), "um único ponto de criação (dentro do protocolo)");
		assertEquals(1, occurrences(service, "pet.reactivate("), "um único ponto de reativação (dentro do protocolo)");
	}

	/**
	 * PR 49: a exigência de pet vive no serviço de agendamento, então só ele pode criar {@code Appointment}. Quem precisar de um caminho
	 * novo (remarcar, reserva sem conta) passa por ele e herda PET_REQUIRED, o bloqueio do pet e a cópia dos dados; o banco ainda recusa
	 * agendamento novo sem pet (V10), mesmo que alguém esqueça.
	 */
	@Test
	void soOServicoDeAgendamentoCriaAppointmentParaQueAExigenciaDePetValhaParaTodoCaminho() throws IOException {
		assertEquals(List.of("BookingService.java"), filesCreating("Appointment"), "só o BookingService cria Appointment");
	}

	@Test
	void criarEReativarPassamPeloProtocoloUnicoQueBloqueiaOCliente() throws IOException {
		String service = Files.readString(MAIN.resolve("com/queuecare/pets/PetService.java"));
		assertEquals(2, occurrences(service, "takeActiveSlot(customerId,"), "criar e reativar chamam o protocolo");
		assertEquals(1, occurrences(service, "lockCustomer(customerId);"), "o bloqueio do cliente está só dentro do protocolo");
		int protocol = service.indexOf("private Pet takeActiveSlot(");
		assertTrue(protocol > 0);
		assertTrue(service.indexOf("lockCustomer(customerId);") > protocol, "o bloqueio do cliente fica dentro do protocolo");
	}

	@Test
	void soOServicoEOBloqueioDeLinhaUsamORepositorio() throws IOException {
		List<String> users = filesContaining("PetRepository");
		assertEquals(List.of("PetRepository.java", "PetService.java", "RowLocks.java"), users,
				"quem grava pets é o serviço; o RowLocks só bloqueia a linha (leitura com bloqueio)");
	}
}
