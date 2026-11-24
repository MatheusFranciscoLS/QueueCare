# ADR 0033: cadastro e reserva pelo balcão

- **Status:** Accepted (implementado em 24/11/2026: API no PR 108, telas no PR 109)
- **Data:** 24/11/2026
- **Esforço:** ALTO (migração, 5 rotas, regra de quem pode agir por quem, 11 testes de integração)

## Contexto: a análise crítica
Num banho e tosa real, a maior parte dos pedidos chega por **telefone ou WhatsApp**: "tem horário sábado de manhã pro Thor?". Até aqui o app só deixava o **tutor** agendar, o que exige que ele crie conta, cadastre o pet e reserve sozinho. Quem liga e não faz isso fica fora da agenda, ou a equipe anota no caderno e a agenda do app mente. Um sistema que não aceita o jeito mais comum de o cliente pedir horário não é de uso diário.

A lista de prioridades trazia "importação de clientes por planilha". Ela resolve a migração do caderno, mas **não** deixa a equipe agendar por quem liga; por isso este ADR vem antes (decisão do dono, 24/11/2026).

## Decisão
1. **A equipe (dono e atendente) cadastra o tutor pelo balcão**: nome e WhatsApp (obrigatórios), e-mail (opcional). Depois cadastra o pet, vê os horários livres e **agenda por ele**. Rotas em `/api/agenda/customers/...` (as do dono e do atendente).
2. **Quem pode agir por quem (a regra central).** A equipe só alcança tutor cuja conta tem `created_by_provider_id` igual ao **seu** estúdio (coluna nova, V27). Conta que o próprio tutor criou **nunca** é alcançada, mesmo que ele já tenha sido atendido ali. Tutor inexistente, de outro estúdio, criado pelo próprio tutor ou que é da equipe têm a **mesma resposta 404**, sem pista (há teste que compara as respostas).
3. **A conta nasce sem acesso.** A senha é aleatória e ninguém a conhece; sem e-mail informado, o e-mail é `balcao-<id>@balcao.invalid` (não recebe nada e não é um endereço que ninguém possa usar). Dar acesso ao tutor é um passo deliberado e posterior (a senha temporária do ADR 0025 já existe; o passo "informar o e-mail e gerar a senha" é o próximo PR).
4. **A reserva é a mesma do cliente.** `CounterBooking` só confere quem pode agir e chama o `BookingService` e o `SlotService` de sempre: horário local, antecedência, expediente, folgas, duração pelo pet, sinal por Pix, e a trava de exclusão do banco contra reserva dupla (o teste reserva o mesmo horário duas vezes). Nenhuma regra foi copiada.
5. **A equipe fica registrada como autora**: o evento "agendado" guarda o id de quem reservou; o histórico mostra como feito pela equipe, não pelo tutor.
6. **Auditoria e limite.** Cada cadastro grava `CUSTOMER_CREATED_BY_STUDIO` (ator e alvo, sem texto livre) e cada estúdio cadastra no máximo 60 tutores por hora (429), porque criar conta é a operação que alguém abusaria para encher o banco.
7. **A lista de Clientes mostra o cadastro desde o primeiro minuto**, antes da primeira reserva, marcando quais vieram do balcão e se têm e-mail.
8. **Editar e arquivar pet continuam do tutor.** A equipe cadastra e lista, mas não reescreve a ficha por esta via (menos poder, menos o que abusar).

## O que foi descartado
- **Agendar por qualquer tutor que o estúdio já atendeu:** a conta é global na plataforma (pets e agendamentos de outros estúdios). Deixar a equipe criar pets e reservas nela é agir sobre dado que não é do estúdio. A exceção "só o que o estúdio criou" é o que mantém a fronteira.
- **Tabela separada de "clientes do balcão":** exigiria duplicar pets, agendamentos, sinal, histórico, privacidade e backup. Reusar `users` com um vínculo reaproveita tudo e a conta pode, depois, ganhar acesso sem migrar dados.
- **Cadastro sem telefone:** o telefone é como o estúdio fala com a pessoa (lembrete e "chamar de volta"); sem ele o cadastro é só um nome.
- **Enviar e-mail ou WhatsApp automaticamente ao cadastrar:** vetado pelo custo zero (ADR 0013) e porque a pessoa não pediu para receber.
- **Importar planilha agora:** passa a reaproveitar este cadastro e vem depois.

## Riscos conhecidos
- **Base legal e direitos do titular (LGPD, ADR 0027).** O estúdio cadastra dados de quem pediu o serviço: a base é a execução do atendimento que a pessoa solicitou. Mas essa pessoa **não aceitou a política na tela** e, sem acesso, **não consegue exportar nem excluir os próprios dados sozinha**. O caminho hoje é pedir ao estúdio, que tem o canal (WhatsApp); falta uma ação do estúdio para atender o pedido (exportar e anonimizar a conta que ele criou). Está registrado como o próximo passo de privacidade, e a política de privacidade deve dizer isso.
- **Duplicidade:** o mesmo tutor pode ser cadastrado duas vezes se a equipe não buscar antes (telefone não é único, porque família divide número). A tela deve buscar primeiro e mostrar quem já existe.
- **Dar acesso depois** muda quem controla a conta: ao gerar a senha, o tutor passa a ver e mexer nos próprios dados, inclusive os que o estúdio cadastrou. É o comportamento esperado, mas o aviso precisa estar na tela.
- **E-mail digitado errado** vira uma conta com um endereço que pertence a outra pessoa, que não recebe nada (não há envio) mas pode, no futuro, se cadastrar com ele e receber o conflito 409. O conflito é uniforme e não revela a conta.

## Consequências
- A equipe agenda por quem liga em menos de um minuto, e a agenda do app passa a ser a verdade do estúdio.
- Zero mudança nas regras de agendamento: o que já estava testado continua valendo para os dois caminhos.
- Uma coluna anulável e um índice parcial (migração só de expansão, compatível com a versão anterior da API em produção).
