# ADR 0040: partida mais rápida com AppCDS (arquivo de classes pré-carregadas)

- **Status:** Accepted (implementado em 24/11/2026; o ganho em produção é conferido pelo log do Render depois do primeiro deploy, veja "Como conferir")
- **Data:** 24/11/2026
- **Esforço:** MÉDIO (um script de treino, quatro linhas no Dockerfile, um passo no CI)

## Contexto: a medição
A API do Render gratuito dorme e a primeira chamada leva **~115 s** para ser respondida (ADR 0037). O log da própria API mostra onde o tempo vai. Em **22 partidas** registradas pelo Render, o `Started QueuecareApiApplication in N seconds` ficou **entre 85 e 104 s** (quase sempre perto de 95 s): é um custo **determinístico de CPU**, não variação de rede. Numa partida típica (horários do log):

| Fase | Duração aproximada |
|---|---|
| Container e JVM até o primeiro log da aplicação | 11 s |
| Contexto web (varredura, configuração) | 21 s |
| Conexão com o banco acordando e Flyway | 12 s |
| Hibernate | 12 s |
| Criação dos demais beans (controladores, segurança, MVC) | 35 s |

Em todas, o gargalo é **carregar e verificar classes numa CPU de 0,1 vCPU**: o mesmo programa, na máquina de desenvolvimento, faz as mesmas fases na mesma proporção, cerca de 10 vezes mais rápido. Isso torna possível **testar ideias localmente** e esperar ganho proporcional no Render.

## Decisão
Usar **AppCDS** (*class data sharing* de aplicação): durante o `docker build` a API roda uma vez, só até o contexto Spring ficar pronto, e o JVM grava em `app/app.jsa` as classes que carregou, já processadas. Na partida de verdade ele **mapeia esse arquivo** em vez de carregar e verificar milhares de classes de novo.

- **Medido localmente com o JAR de produção** (mesmos flags da imagem, contexto até ficar pronto, sem banco): **5,58 s → 3,66 s (−34%)**. Com a API de desenvolvimento completa (PostgreSQL embutido), 10,4 s → 8,4 s (−19%, e isso inclui ~3 s fixos do banco embutido).
- **O treino roda sem banco** (`cds/treino.sh`): Flyway desligado, esquema sem validação, dialeto explícito e metadados do JDBC desligados para o Hibernate não abrir conexão, e o pool sem falhar por não achar o banco. Os valores são de mentira, só existem durante o comando e nada é gravado na imagem.
- **O JAR é extraído** (`-Djarmode=tools extract`): o JVM só mapeia arquivo de classes de JARs soltos, não de diretórios nem do JAR aninhado do Spring Boot.
- **Travas:** (1) se o treino falhar, o **build** falha e a versão atual da API continua no ar; (2) se o JVM recusar o arquivo na partida (JDK diferente, por exemplo), ele **segue sem ele**, que é o comportamento de antes; (3) o **CI sobe a imagem usando o arquivo com `-Xshare:on`**, que faz o JVM *falhar* se o recusar, e imprime nos logs o tempo com e sem o arquivo.

## O que foi descartado
- **`-XX:+AutoCreateSharedArchive`:** criaria o arquivo na primeira execução, mas o disco do Render gratuito é efêmero (cada partida começa do zero) e gravar o arquivo na saída gastaria CPU justamente no desligamento.
- **Spring AOT:** reduziria ainda mais a partida, mas fixa condições de configuração no build e muda a forma de inicializar todo o programa; o risco não compensa antes de medir o AppCDS.
- **Inicialização preguiçosa (`lazy-initialization`):** a aplicação "sobe" antes, mas a primeira chamada paga o que foi adiado, e erros de configuração só apareceriam em produção, na primeira chamada.
- **Desligar a verificação de bytecode** (`-XX:-BytecodeVerificationRemote`): ganha tempo abrindo mão de uma proteção do JVM.
- **Aquecer por serviço externo:** é a solução que elimina a espera, mas é uma ação do dono (conta de terceiros) e só no horário comercial (ADR 0037); este ADR reduz o custo de cada partida, que continua existindo depois de cada deploy.

## Riscos conhecidos
- **O arquivo tem ~100 MB**: a imagem cresce na mesma medida, e ele é mapeado em memória (páginas só de leitura, recuperáveis pelo sistema). A memória em produção deve ser conferida no painel depois do deploy (o limite é 512 MB).
- **O arquivo só vale com o mesmo JDK e os mesmos flags**: a imagem base é fixada por digest e a variável `JAVA_TOOL_OPTIONS` é definida antes do treino, então os dois lados coincidem; trocar o digest refaz o treino sozinho.
- **O treino cobre o contexto sem banco**: as classes que só carregam com a conexão (Flyway executando, Hibernate validando) não estão no arquivo e continuam sendo carregadas normalmente.
- **Não foi medido no Render antes de publicar** (não há como reproduzir a CPU de 0,1 localmente): o ganho local de 34% é uma estimativa, e a medição real vem do log do primeiro deploy.

## Como conferir (depois do deploy)
No painel do Render, o log da partida traz `Started QueuecareApiApplication in N seconds`. A linha de base é de 85 a 104 s (22 partidas). Se o valor não cair de forma clara, o arquivo não está sendo usado ou não ajuda ali, e o caminho é reverter este PR.
