# Redesign R6: a Agenda em grade (semana), o Dia em linhas e a Fila (estudo)

Parte do ADR 0018 v2 (revisão v2.1). A primeira versão da semana (uma lista por dia em colunas estreitas) e o "fio do dia" no Dia foram rejeitados pelo dono ("feio") e, com a segunda opinião do ChatGPT, **substituídos** por um desenho de agenda profissional. Fluxo: a pedido do dono, o miolo foi redesenhado com a skill de design, mostrado em prévia navegável e comparado pelo ChatGPT com a versão anterior (ele preferiu a grade, as linhas e a fila novas e apontou as causas de "feio": pouca hierarquia entre informação e decoração, densidade mal distribuída, cor demais e pet pouco protagonista).

## O que muda
- **Semana = grade de horários** (`WeekView`): cada atendimento é um bloco na hora dele, com a **altura da duração**, uma coluna por dia (dia e hora são os do **negócio**, fuso IANA; a reserva impede atendimentos sobrepostos do mesmo prestador, então não há blocos lado a lado). Eixo das horas de 8 a 18, estendido se houver atendimento fora dele; linha dourada de "agora" no dia de hoje; hoje com tom suave. O bloco mostra horário, nome do pet e serviço (o serviço some nos blocos curtos); o **estado** vem do tom, de uma **forma** (anel, meio, cheio) no canto e do **texto** (para leitores de tela). Clicar no bloco ou no título do dia abre o dia.
- **Regra pela largura da ÁREA DE CONTEÚDO** (consulta de contêiner, não da janela): grade a partir de 880 px; abaixo disso, **uma seção por dia**, com os mesmos dados em lista (avatar, serviço, raça e porte, estado em texto).
- **Dia = linhas limpas** dentro de uma folha (sem fio, sem cartões repetidos): hora, **avatar do pet** (desenho de cachorro ou gato; a foto, quando existir, entra no mesmo lugar), **nome do pet em destaque** com raça e porte em texto de apoio, tutor, serviço, observações, estado em pílula (com a forma) e as ações. Marcador "agora" (etiqueta e linha douradas) entre o último atendimento que já começou e o próximo.
- **Fila = linhas** com a **posição** em número grande, avatar, pet, horário marcado e espera; sem o fio.
- **Barra de ferramentas da agenda** em uma faixa: controle segmentado Dia/Semana, navegação e data. A visão mora no endereço (`?visao=semana`).
- **Componentes novos:** `Segmented` (Dia/Semana) e `PetAvatar` (cachorro/gato; decorativo, escondido de leitores de tela).
- **O fio saiu da Agenda**: era a ideia do nome, mas a grade é o que um estúdio profissional espera e o dono rejeitou o cronograma na entrada. O ADR foi atualizado.

## Sem mudar o comportamento
O Dia e a Fila mudam só de CSS e do avatar (decorativo); a lógica (chegada, conclusão, remarcação, cancelamento com confirmação na linha, foco, histórico) é a mesma e segue coberta pelos testes. A semana usa `GET /api/agenda/week` (já existia).

## Evidências
- 391 testes (novos: semana, datas da semana, `Segmented`, `PetAvatar`; ordem de leitura e marcador "agora" do Dia já cobertos).
- Navegador real (Edge): `e2e/semana.spec.ts` (grade a 1280 px; uma seção por dia a 1024, 768 e 390; sem rolagem horizontal de 320 a 1280) e modo estrito nas telas Agenda do prestador, Agenda do atendente, Semana e Fila, claro e escuro, 1280 e 390 px (24 combinações): axe 0, foco 0, rolagem 0, alvos 0. Dois achados da verificação corrigidos (anel de foco do controle segmentado sobre fundo escuro; anel cortado em blocos vizinhos e no cabeçalho do dia).
