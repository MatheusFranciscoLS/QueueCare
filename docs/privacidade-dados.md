# Dados pessoais no QueueCare: inventário, retenção e incidentes

Apoio técnico do projeto (ADR 0027). **Não é parecer jurídico.** Serve para saber, em um lugar, o que é guardado, onde, por quanto tempo e como cada direito é atendido.

## 1. Inventário

| Dado | Onde | Para quê | Quem vê | Sai quando |
|---|---|---|---|---|
| Nome, e-mail | `users` | Identificar a pessoa e entrar | A própria pessoa; o estúdio vê o nome (e o telefone) de quem agenda com ele. **O e-mail não aparece** na busca de clientes do estúdio | Exclusão da conta (anonimizado) |
| Telefone (opcional) | `users.phone` | O estúdio chamar pelo WhatsApp | A pessoa e a equipe do estúdio onde ela agenda | Exclusão da conta; ou a pessoa limpa em Meu perfil |
| Senha | `users.password_hash` | Entrar | Ninguém (só o hash BCrypt) | Troca ou exclusão (vira um hash que ninguém conhece) |
| Versão da política lida | `users.privacy_policy_version/at` | Provar qual texto estava no ar | A própria pessoa (na exportação) | Fica na conta anonimizada |
| Pets (nome, raça, porte, pelo, peso, temperamento, vacinas, observações e saúde) | `pets` | O estúdio atender com segurança | O tutor e a equipe do estúdio que atendeu | Exclusão da conta (anonimizado); o tutor edita ou arquiva |
| Cópia dos dados do pet na reserva | `appointments.pet_*` | Histórico fiel do atendimento | Tutor e equipe do estúdio | Exclusão da conta (anonimizada) |
| Agendamentos (estúdio, serviço, horário, estado, valor, sinal) | `appointments` | Operação do estúdio | Tutor e equipe do estúdio | Ficam sem identificar a pessoa (registro do estúdio) |
| Eventos do agendamento | `appointment_events` | Histórico (quem fez o quê) | Tutor (sem nome de funcionário) e equipe | Ficam (nome da pessoa não está neles) |
| Lista de espera | `waitlist_entries` | Reaproveitar horário que vagou | Tutor e equipe | Exclusão da conta (removida) |
| Auditoria | `audit_log` | Rastro de ações sensíveis (senha, contas, anonimização) | Administrador; somente de acréscimo, sem texto livre | Não sai: aponta para a conta anonimizada |
| Logs técnicos | Hospedagem (Render, Vercel) | Operar o serviço | Quem opera | Retenção do provedor |

O app **não** usa cookies de rastreamento, não tem analytics de terceiros nem anúncios. A sessão fica em memória do navegador.

## 2. Como cada direito é atendido

| Direito | Caminho no app |
|---|---|
| Acesso e portabilidade | Meu perfil → **Baixar meus dados** (`GET /api/me/export`, JSON) |
| Correção | Meu perfil (telefone), Meus pets (pets) |
| Eliminação | Meu perfil → **Excluir minha conta** (`POST /api/me/delete-account`): anonimiza no lugar, cancela horários marcados, esvazia a lista de espera |
| Informação | Política de Privacidade (`/privacidade`), linkada no cadastro, na entrada e no menu |
| Revogar / reclamar | Contato do responsável (perfil do repositório no GitHub) e ANPD |

### Tutor cadastrado pelo balcão (ADR 0033)
Quando a equipe do estúdio cadastra quem pediu horário por telefone ou WhatsApp, a conta guarda nome, telefone e, se informado, e-mail, mais `users.created_by_provider_id` (qual estúdio criou). A base é a execução do atendimento que a pessoa pediu; ela **não viu nem aceitou a política na tela** e, **sem acesso ao app, não consegue exportar nem excluir os próprios dados sozinha**. Até existir uma ação do estúdio para isso, o pedido é atendido pelo contato do responsável: o estúdio avisa, e o pedido de eliminação se resolve por **anonimização da conta** (mesmo efeito do ADR 0027). Cada cadastro fica na auditoria (`CUSTOMER_CREATED_BY_STUDIO`). A equipe só age por contas que o **próprio estúdio criou**.

## 3. Retenção

- **Conta e pets:** enquanto existirem. Não há exclusão automática por inatividade (decisão consciente: sem rotina agendada nesta fase). Recomendação para um uso real: revisar contas sem acesso há mais de 24 meses.
- **Agendamentos e eventos:** ficam, sem identificar a pessoa depois da exclusão (registro operacional e financeiro do estúdio).
- **Auditoria:** permanente por desenho.
- **Backups:** as cópias antigas guardam o dado como era até expirarem; a retenção deles (14 dias) está em `docs/backup.md`. Restaurar um backup **não pode** reativar uma conta já excluída: depois de restaurar, repetir as exclusões pedidas desde a data do backup.

## 4. Incidente com dados pessoais (resposta mínima)

1. **Conter:** trocar `JWT_SECRET` (invalida todas as sessões), revogar a credencial do banco exposta, desativar a conta suspeita (Admin → Contas).
2. **Avaliar:** o que vazou, de quantas pessoas, por quanto tempo (a auditoria e os logs do provedor ajudam).
3. **Registrar:** data, descrição, dados envolvidos, medidas tomadas.
4. **Comunicar:** as pessoas afetadas e, quando houver risco relevante, a ANPD, no prazo que a regulamentação vigente indicar (confirmar o prazo atual na página da ANPD, que muda).
5. **Corrigir e aprender:** causa, correção e um teste que impeça de voltar.
