import { apiUrl } from './base'

/**
 * Acorda a API assim que o site abre (ADR 0037). Na hospedagem gratuita a API dorme sem uso e a primeira chamada leva até ~2 minutos (medido: 115 s). Sem isto, o tempo
 * só começa a correr quando a pessoa clica em Entrar; com isto, ele corre enquanto ela lê a página e digita, e na maioria das vezes a espera some ou encurta muito.
 *
 * **Chama a prontidão (que consulta o banco), e não a vivacidade**: o banco Neon também dorme (suspende após 5 min sem uso) e acordá-lo leva ~7 s (medido: o primeiro login com a API acordada levou 7 017 ms); assim ele acorda junto, enquanto a pessoa lê a página. Uma chamada só, sem credencial, sem dado nenhum e sem ler a resposta (`no-cors`: não depende de permissão de origem, e a resposta opaca nem é usada). Falha em silêncio: o
 * objetivo é só acordar o servidor, e qualquer erro de verdade aparece na primeira chamada real. Em desenvolvimento (sem VITE_API_URL) não faz nada: a API local não dorme.
 */
export function warmUpServer(): void {
  if (String(import.meta.env.VITE_API_URL ?? '').trim() === '') return
  try {
    void fetch(apiUrl('/actuator/health/readiness'), { method: 'GET', mode: 'no-cors', credentials: 'omit', cache: 'no-store' }).catch(() => undefined)
  } catch {
    // endereço da API inválido: o erro de configuração aparece, alto, na primeira chamada real
  }
}
