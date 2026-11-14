# QueueCare: instruções permanentes para o Claude Code

Escrito em português do Brasil: conversa, comentários, mensagens de commit e documentação (a documentação em inglês entra só na fase 3).

## 1. O projeto
Plataforma Full Stack de agendamento e fila de atendimento para pequenos prestadores de serviço (oficina de assistência técnica, clínica pequena, barbearia...). Perfis: ADMIN (administrador de contas, sem acesso a agendas nem a dados de clientes), PROVIDER (prestador), ATTENDANT (atendente), CUSTOMER (cliente).

Stack: React + TypeScript · Java 21 + Spring Boot · Spring Security · Spring Data JPA · PostgreSQL · Flyway · JUnit + MockMvc + Testcontainers · Playwright · Docker · GitHub Actions · (fase 2) hospedagem de custo zero: Cloudflare Pages + Render Free + Neon, sem cartão (ADR 0013) · (fase 3) documentação em inglês.
Monólito modular Spring Boot (sem microsserviços, sem RabbitMQ no MVP). Não ampliar o escopo: veja `docs/roadmap.md`.

Leia antes de qualquer tarefa: `docs/regras.md`, `docs/modelo-dados.md`, `docs/arquitetura.md` e os ADRs em `docs/adr/`. O repositório é a fonte oficial das decisões, não a memória da conversa.

## 2. Regra de autoria (a mais importante; atualizada em 13/11/2026)
O Matheus é o dono do projeto e decidiu, em 13/11/2026, **delegar a implementação ao Claude e a revisão ao ChatGPT**: o Claude implementa (inclusive as regras centrais: disponibilidade, sobreposição, cancelamento, autorização, fuso e antecedência, e os testes) e o ChatGPT revisa de forma independente; os dois decidem juntos o que fazer primeiro. Ele define o produto, aprova o rumo e recebe resumos em linguagem simples. (Antes disso, a regra era que ele escrevesse essas partes; foi substituída por esta.)

Condições combinadas com o ChatGPT (revisor independente):
- **Autoria honesta:** o README declara que o projeto foi desenvolvido com assistência substancial de IA, sob a direção do dono. Nunca afirmar que o Matheus escreveu o que ele não escreveu.
- **Material de estudo por PR:** cada PR traz, em `docs/estudo/`, um resumo em linguagem simples, 3 perguntas de entrevista (sem respostas para decorar), limitações e testes que ainda faltam. Manter `docs/estudo/visao-geral.md` atualizado.
- **Revisão independente antes de mesclar:** o ChatGPT revisa cada PR (ver seção 7).
- **Validação em ambiente real:** migration ou proteção contra reserva dupla só é considerada concluída com PostgreSQL real e o teste T3. Não adiantar SQL que não se pode executar.
- Quando o Matheus quiser voltar a escrever alguma parte, deixe `// TODO(matheus):` claro no ponto dele.

## 3. Método
1. Explicar o problema e o conceito antes de propor código.
2. Quando houver decisão de arquitetura, mostrar alternativas e escrever/atualizar um ADR (`docs/adr/`) antes de implementar.
3. O Claude implementa em branch isolada, o ChatGPT revisa de forma independente, o Claude corrige e mescla; o Matheus recebe o resumo.
4. Apontar erros e riscos com justificativa (BLOCKER, HIGH, MEDIUM, LOW).
5. Nunca esconder complexidade importante atrás de abstração desnecessária. Não reescrever código funcional por preferência estética.
6. Não presumir conhecimento avançado de Java ou Spring; explicar termos.

## 4. Regras de engenharia
- Arquitetura modular e simples. Não duplicar regra de negócio.
- Não confiar só na validação do front-end: a API valida e autoriza.
- Senhas com hash (nunca texto puro); segredos em variáveis de ambiente; nada sensível em log; dados de teste fictícios.
- Alterações de banco só por migration Flyway.
- Transações onde necessário; integridade também no PostgreSQL (constraints).
- Conflito de agendamento devolve HTTP 409.
- Horários guardados como instantes UTC (`timestamptz`); o prestador tem um fuso IANA (ex.: `America/Sao_Paulo`); converter considerando horário local inexistente ou ambíguo.
- Sobreposição de horários: decidir no ADR 0002 (preferência inicial: `EXCLUDE USING gist` com `tstzrange` e `btree_gist`, intervalos `[)`, ignorando cancelados).

## 5. Git, PRs e segurança do fluxo
- Nunca trabalhar direto na `main`. Branches: `feat/`, `fix/`, `test/`, `docs/`, `infra/`.
- Commits pequenos e descritivos, em português. PRs pequenos, por funcionalidade, com o que mudou e quais testes foram executados.
- Autorizado pelo Matheus (13/11/2026): push, abrir PR e **mesclar** neste repositório, seguindo as condições abaixo.
- **Critérios mínimos para mesclar:** CI verde (Backend, Frontend e Gitleaks); revisão independente do ChatGPT sem BLOCKER/HIGH pendente; MEDIUM resolvido ou aceito com justificativa; testes proporcionais ao risco; documentação e autoria atualizadas; nenhuma afirmação de teste sem execução real. CI verde sozinho não basta.
- **Continuam exigindo pergunta ao Matheus:** criar recursos de nuvem ou qualquer custo, tornar privado ou apagar o repositório, trocar a licença, publicar fora deste repositório e comandos destrutivos.
- Em Windows, não rodar comandos destrutivos (`reset --hard`, `clean -fd`, `push --force`) sem pedir.

## 6. Qualidade (antes de dizer "pronto")
Compilar; executar os testes relevantes; revisar erros, segurança e permissões; checar regressões; atualizar documentação. **Nunca afirmar que testou sem ter rodado.** Relatar falhas com a saída real.

## 7. Divisão de trabalho
- **Matheus:** dono do produto; decide o rumo, aprova o que envolve custo ou exposição pública, faz logins e contas (hospedagem SÓ em plano gratuito e sem cartão: se um serviço pedir cartão, parar e avisar) e recebe os resumos.
- **Claude (esta sessão):** decide junto com o ChatGPT e implementa (código, testes, CI, Docker, documentação e material de estudo) e mescla conforme a seção 5.
- **ChatGPT (projeto "QueueCare | Desenvolvimento Full Stack"):** revisor independente de PRs, cenários de teste, entrevista técnica. **Você conversa diretamente com ele** (o Matheus não é o mensageiro): antes de decisões importantes, ADRs, mudanças de ambiente e a cada PR, discuta com ele e traga ao Matheus uma recomendação única em linguagem simples, com as divergências. O passo a passo (links do projeto do ChatGPT e armadilhas do navegador) fica fora do repositório, em `C:\dev\QueueCare-local\COMO-CONVERSAR-COM-O-CHATGPT.md`, porque contém links privados.
- Assuntos que não são do QueueCare (carreira, currículos etc.) NÃO são tratados aqui.
- **Nunca versionar** notas privadas, links de conversas, dados pessoais, segredos ou caminhos locais: esse material fica em `C:\dev\QueueCare-local\`, fora do repositório.

## 8. Objetivo final
Um produto de portfólio funcional e bem testado, apresentado com honestidade sobre o papel da IA, e o Matheus capaz de explicar a visão geral, as principais decisões e as limitações do sistema (ver `docs/estudo/`).
