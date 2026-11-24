# ADR 0039: atualização e auditoria de dependências

- **Status:** Accepted (implementado em 24/11/2026)
- **Data:** 24/11/2026
- **Esforço:** BAIXO (um arquivo de configuração e uma linha no CI)

## Contexto: a análise crítica
A CI procurava **segredos** (Gitleaks), mas nada avisava quando uma **dependência** ganhava correção de segurança: não havia `dependabot.yml` e os alertas de vulnerabilidade do repositório estavam desligados. Uma biblioteca com falha conhecida podia ficar meses na produção sem que ninguém soubesse. Medido em 24/11/2026: o frontend não tinha nenhuma vulnerabilidade conhecida (`npm audit`) e o backend (Spring Boot 4.1.2) não tinha atualização pendente. Ou seja, o estado estava bom; **o que faltava era o mecanismo que o mantém assim**.

## Decisão
1. **Dependabot** (`.github/dependabot.yml`): PRs **semanais** para Maven e npm (agrupando atualizações menores e de correção em um PR só, para não gerar ruído), **mensais** para GitHub Actions e para as imagens base do Dockerfile (que são fixadas por digest: o Dependabot propõe o digest novo e a CI constrói a imagem para provar que continua válida).
2. **Atualizações MAIORES ficam de fora** do automático: mudam a API, pedem ler as notas de versão e decidir. Entram por decisão, não por PR de robô.
3. **Auditoria no CI** do frontend: `npm audit --omit=dev --audit-level=high` derruba o PR se uma dependência de **produção** tiver vulnerabilidade conhecida alta ou crítica. Dependências só de desenvolvimento (testes, lint) ficam de fora: não chegam ao navegador de ninguém.
4. **Cada PR do Dependabot passa pela mesma CI** (testes, backup, imagem, acessibilidade): uma atualização que quebra algo é barrada antes de entrar.

## O que foi descartado
- **OWASP Dependency-Check no CI do backend:** baixa a base de vulnerabilidades (NVD) a cada execução, o que leva minutos e é instável sem chave de API; para o tamanho do projeto, os alertas do próprio GitHub (Dependabot alerts, com a base de advisories do GitHub) cobrem o mesmo com custo zero e sem lentidão no CI.
- **Falhar o CI também por vulnerabilidade moderada ou baixa:** gera PRs bloqueados por avisos sem correção disponível e sem como ser explorado aqui. O limite é alto e crítico.
- **Atualizar tudo automaticamente, inclusive versões maiores:** troca previsibilidade por velocidade; num projeto sem equipe de plantão, o risco não compensa.

## Riscos conhecidos
- **Um aviso novo pode barrar um PR sem relação com ele** (o `npm audit` olha para o estado atual das dependências, não para a mudança do PR). É o preço de uma checagem que não envelhece; a correção costuma ser um `npm audit fix` ou uma atualização do Dependabot.
- **Os alertas de vulnerabilidade e as atualizações automáticas de segurança dependem de uma configuração do repositório** (Settings, Code security), que só o dono liga. O `dependabot.yml` sozinho agenda as atualizações de versão, mas não ativa os alertas.
- **Sem varredura de dependências do backend no CI:** a cobertura do lado Java vem dos alertas do GitHub, que só funcionam depois de ligados.

## Consequências
- Dependências novas chegam por PR pequeno e validado, toda segunda-feira, em vez de acumular.
- Uma vulnerabilidade conhecida em produção no frontend passa a derrubar o PR, e não a ser descoberta por acaso.
