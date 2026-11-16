# Redesign R2: fundação visual (estudo)

Parte do ADR 0018 v2. Este PR cria a **fundação**: tokens, fontes, temas e componentes base, **sem migrar nenhuma tela** e sem mudar o visual do produto.

## O que entra
- `frontend/src/ui/ui.css`: tokens sob a classe `.pp` (cores medidas, tipografia, espaço), tema claro e escuro, estilos dos componentes e do **fio**; a regra de **algarismos** (`@font-face` com `unicode-range` dos dígitos, apontando para o mesmo arquivo da Bricolage).
- `components.tsx`: `Button` (carregando sem perder o foco, sem envio duplo), `Field` (rótulo, dica e erro ligados por `aria-describedby`, erro com `role="alert"`), `Choice`, `SlotButton` (indisponível dito em texto), `StateDot` e `StateBadge` (estado sempre em texto), `Notice` (`alert` para erro e aviso, `status` para o resto).
- `theme.ts` e `ThemeToggle.tsx`: segue o sistema; claro e escuro forçados; `localStorage` com `try/catch`; script em linha no `index.html` aplica o tema salvo antes da primeira renderização.
- `DemoPage.tsx`: página `/__ui` **só em desenvolvimento** (`import.meta.env.DEV`; no build de produção a rota e o código não existem).

## Por que escopado em `.pp`
As telas atuais usam variáveis globais com outros nomes. Os tokens novos moram em `.pp` (e os do tema em `:root[data-theme]`) para a migração ser tela a tela, sem quebrar nada no meio.

## Evidências
- Testes de comportamento: 16 novos (341 no total). Mutação manual: 5 mutantes mortos (guarda de carregamento, `aria-invalid`, `role` do aviso, leitura do tema salvo, falha do armazenamento).
- Navegador real (Edge), `QC_STRICT=1` na página de demonstração, claro e escuro, 1280 e 390 px: axe graves/críticas 0; foco sem anel 0; rolagem horizontal 0; alvos menores que 44 px 0.
- Peso: o build de produção **não** inclui as fontes novas nem a demonstração (nenhum arquivo `bricolage`/`atkinson` em `dist/assets`); o JS inicial segue em ~104,5 kB gzip.
