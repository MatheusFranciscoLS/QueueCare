import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { ACCOUNTS, PASSWORD, entrar, irPeloMenu, type Perfil } from './support'

/**
 * Fluxos de ponta a ponta das novidades dos ADRs 0020 a 0023 (R12), no navegador real e na API de demonstração (dados FICTÍCIOS de LocalDevServer).
 * A API só MONTA cenário (conta, pet, lista de espera); tudo que a pessoa faz é pela interface. Em série: um fluxo pode usar o que o anterior deixou.
 *
 * ATENÇÃO: estes fluxos ALTERAM dados (falta, lembrete, lista de espera, reservas). Rode com a API de demonstração RECÉM-SUBIDA: numa segunda rodada na mesma
 * API o agendamento de ontem já terá sido marcado como falta. (Não roda no CI do GitHub, como o resto do Playwright.)
 *
 * Conta do tutor novo (Paulo): o ambiente de demonstração pede sinal de quem ainda não foi atendido no estúdio (regra NEW_OR_REPEAT_NO_SHOW, R$ 30,00).
 */
test.describe.configure({ mode: 'serial' })

const API = 'http://localhost:8080'
const hoje = new Date()
const dia = (aFrente: number) => {
  const d = new Date(hoje.getTime() + aFrente * 86_400_000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(d) // AAAA-MM-DD
}

async function token(request: APIRequestContext, perfil: Perfil): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, { data: { email: ACCOUNTS[perfil], password: PASSWORD } })
  expect(res.ok(), `login de ${perfil} na API`).toBeTruthy()
  return (await res.json()).accessToken as string
}

/** O WhatsApp abre numa aba nova: no teste ele nunca sai para a internet (a rota é cortada), mas o clique ainda dispara o registro na API. */
async function bloquearWhatsapp(page: Page) {
  await page.context().route('https://wa.me/**', (route) => route.abort())
}

/** Anda pela Agenda do estúdio, dia a dia, até achar algo na tela (a demonstração tem horários em dias diferentes conforme a hora em que roda). */
async function acharNaAgenda(page: Page, texto: RegExp | string, maximo = 5) {
  for (let i = 0; i < maximo; i++) {
    // espera o dia carregar: ou há agendamentos, ou o aviso de dia vazio (o aviso de servidor lento também é .notice, mas fica escondido)
    await page.locator('.appt').first().or(page.getByText('Nenhum agendamento neste dia.')).waitFor()
    if (await page.getByText(texto).first().isVisible().catch(() => false)) return
    await page.getByRole('button', { name: 'Próximo dia' }).click()
    await page.waitForLoadState('networkidle')
  }
  throw new Error(`não achei ${String(texto)} na Agenda nos próximos ${maximo} dias`)
}

test('tutora agenda com o pet antes do dia, vê a duração do pet e a política antes de confirmar', async ({ page }) => {
  await entrar(page, 'cliente')
  await irPeloMenu(page, 'Agendar', 1280)
  await page.getByRole('heading', { level: 1, name: 'Agendar' }).waitFor()
  await page.getByRole('radio', { name: /^Banho(?! e tosa)/ }).check()
  await expect(page.getByText('3. Escolha o pet')).toBeVisible() // o pet vem ANTES do dia e do horário (a duração depende dele)
  await expect(page.getByText('4. Escolha o dia')).toHaveCount(0)
  await page.getByRole('radio', { name: /Thor/ }).check()
  await expect(page.getByText('4. Escolha o dia')).toBeVisible()
  await page.locator('.slots .button').first().click()

  const confirmar = page.getByRole('region', { name: 'Confirmar agendamento' })
  await expect(confirmar).toContainText('Banho (120 min) para Thor') // grande (90) + pelo duplo (30)
  await expect(confirmar).toContainText(/Você pode cancelar ou remarcar de graça até/)
  await expect(confirmar).not.toContainText(/sinal de/) // Marina já foi atendida no estúdio: sem sinal
  await confirmar.getByRole('button', { name: 'Confirmar agendamento' }).click()
  await expect(page.getByRole('heading', { name: 'Agendamento confirmado' })).toBeVisible()

  await irPeloMenu(page, 'Meus agendamentos', 1280)
  await page.getByRole('heading', { level: 1, name: 'Meus agendamentos' }).waitFor()
  await expect(page.getByText(/Cancelar ou remarcar de graça até/).first()).toBeVisible()
})

test('visitante vê a página pública com a política, e o endereço que não existe tem a mesma resposta de sempre', async ({ browser }) => {
  const contexto = await browser.newContext()
  const page = await contexto.newPage()
  await page.goto('/p/estudio-bem-te-vi')
  await expect(page.getByRole('heading', { level: 1, name: 'Estúdio Bem-te-vi' })).toBeVisible()
  await expect(page.getByText('a partir de 60 min')).toBeVisible() // o Banho varia com o porte e o pelo
  await expect(page.getByText(/de graça até 12 h antes do horário marcado/)).toBeVisible()
  await expect(page.getByRole('link', { name: 'Entrar para agendar' })).toHaveCount(2) // no topo e no fim da página
  await expect(page.getByRole('link', { name: 'Entrar para agendar' }).first()).toHaveAttribute('href', '/entrar')
  await expect(page.getByRole('link', { name: 'Criar conta de cliente' }).first()).toHaveAttribute('href', '/cadastro')

  await page.goto('/p/nao-existe-mesmo')
  await expect(page.getByRole('heading', { level: 1, name: 'Estúdio não encontrado' })).toBeVisible()
  await contexto.close()
})

test('estúdio registra a falta e o horário volta para a agenda; lembra o tutor de amanhã pelo WhatsApp', async ({ page }) => {
  await bloquearWhatsapp(page)
  await entrar(page, 'prestador')
  await irPeloMenu(page, 'Agenda', 1280)
  await page.getByRole('heading', { level: 1, name: 'Agenda' }).waitFor()

  // O agendamento de ontem ficou sem desfecho: a equipe registra que a pessoa faltou (estado final, com confirmação).
  await page.getByRole('button', { name: 'Dia anterior' }).click()
  await page.locator('.appt').first().waitFor()
  const linha = page.locator('.appt', { hasText: 'João Ferreira' })
  await linha.getByRole('button', { name: /^Mais ações de/ }).click() // "Faltou" fica atrás de "Mais ações"
  await linha.getByRole('button', { name: 'Faltou', exact: true }).click()
  await expect(page.getByText(/Registrar que João Ferreira faltou\? Não dá para desfazer\./)).toBeVisible()
  await page.getByRole('button', { name: 'Confirmar falta' }).click()
  await expect(page.getByText('Falta de João Ferreira registrada.')).toBeVisible()
  await expect(page.locator('.appt', { hasText: 'João Ferreira' }).locator('.badge')).toHaveText('Faltou')
  await expect(page.locator('.appt', { hasText: 'João Ferreira' }).getByRole('button', { name: 'Faltou', exact: true })).toHaveCount(0) // final

  // O Início avisa quantos de amanhã estão sem lembrete e abre a Agenda já em amanhã (atalho ?data=).
  await irPeloMenu(page, 'Início', 1280)
  await expect(page.getByText(/Amanhã:.*sem lembrete/)).toBeVisible()
  await page.getByRole('link', { name: 'Lembrar pelo WhatsApp', exact: true }).click()
  await page.getByRole('heading', { level: 1, name: 'Agenda' }).waitFor()
  await expect(page.getByLabel('Data')).toHaveValue(dia(1))
  await page.locator('.appt').first().waitFor()
  // Quem ainda não foi lembrado tem o botão; o clique abre o WhatsApp (cortado aqui) e guarda quando.
  const lembrar = page.getByRole('link', { name: /^Lembrar .* pelo WhatsApp$/ }).first()
  await expect(lembrar).toHaveAttribute('href', /^https:\/\/wa\.me\/55\d+\?text=/)
  await lembrar.click({ noWaitAfter: true })
  await expect(page.getByText(/Lembrete enviado em \d\d\/\d\d, \d\d:\d\d/).first()).toBeVisible()
})

test('lista de espera: o tutor entra, a equipe vê e chama pelo WhatsApp, e tirar da lista some das duas telas', async ({ page, request }) => {
  await bloquearWhatsapp(page)
  // Cenário pela API: Paulo (tutor novo, sem pets) ganha um pet e entra na lista de um dia daqui a 3 dias.
  const tutor = await token(request, 'clienteNovo')
  const auth = { Authorization: `Bearer ${tutor}` }
  // (sem telefone o app não tem como chamar o tutor, e por isso não oferece o botão: o cenário dá um telefone ao Paulo)
  const fone = await request.put(`${API}/api/me/phone`, { headers: auth, data: { phone: '(11) 95555-0004' } })
  expect(fone.ok(), 'telefone do tutor na API').toBeTruthy()
  const pet = await request.post(`${API}/api/customer/pets`, { headers: auth, data: { name: 'Pipoca', species: 'DOG', size: 'MEDIUM', coat: 'CURLY' } })
  expect(pet.ok(), 'cadastro do pet na API').toBeTruthy()
  const petId = (await pet.json()).id as string
  const providers = (await (await request.get(`${API}/api/catalog/providers?page=0&size=50`, { headers: auth })).json()) as { id: string }[]
  const services = (await (await request.get(`${API}/api/catalog/providers/${providers[0].id}/services`, { headers: auth })).json()) as { id: string; name: string }[]
  const banho = services.find((s) => s.name === 'Banho')!
  const alvo = dia(3)
  const entrou = await request.post(`${API}/api/waitlist`, { headers: auth, data: { providerId: providers[0].id, serviceId: banho.id, petId, date: alvo } })
  expect(entrou.status()).toBe(201)

  // Tutor: vê a entrada em Meus agendamentos.
  await entrar(page, 'clienteNovo')
  await irPeloMenu(page, 'Meus agendamentos', 1280)
  const minha = page.getByRole('region', { name: 'Lista de espera' })
  await expect(minha).toContainText('Banho em Estúdio Bem-te-vi')
  await expect(minha).toContainText('Pipoca')
  await expect(minha).toContainText('Isso não reserva o horário')

  // Estúdio: vê quem espera naquele dia, com a duração prevista para o pet, e chama pelo WhatsApp.
  const estudio = await page.context().newPage()
  await bloquearWhatsapp(estudio)
  await entrar(estudio, 'prestador')
  await irPeloMenu(estudio, 'Agenda', 1280)
  await estudio.getByRole('heading', { level: 1, name: 'Agenda' }).waitFor()
  await estudio.getByLabel('Data').fill(alvo)
  const espera = estudio.getByRole('region', { name: 'Lista de espera' })
  await expect(espera).toContainText('Pipoca')
  await expect(espera).toContainText(/Banho, cerca de \d+ min/)
  await espera.getByRole('link', { name: /^Chamar .* pelo WhatsApp$/ }).click({ noWaitAfter: true })
  await expect(espera.getByText(/Chamado em \d\d\/\d\d, \d\d:\d\d/)).toBeVisible()
  await espera.getByRole('button', { name: /^Tirar da lista:/ }).click()
  await expect(estudio.getByRole('region', { name: 'Lista de espera' })).toHaveCount(0)

  // Tutor: a entrada que a equipe tirou também some da tela dele (depois de recarregar a lista).
  await irPeloMenu(page, 'Meus pets', 1280)
  await page.getByRole('heading', { level: 1, name: 'Meus pets' }).waitFor() // as telas carregam sob demanda: sem esperar, o 2º clique cancela a ida e a lista antiga nunca recarrega
  await irPeloMenu(page, 'Meus agendamentos', 1280)
  await page.getByRole('heading', { level: 1, name: 'Meus agendamentos' }).waitFor()
  await expect(page.getByRole('region', { name: 'Lista de espera' })).toHaveCount(0)
  await estudio.close()
})

test('tutor novo vê o sinal por Pix antes de confirmar, e a equipe marca o sinal como recebido', async ({ page }) => {
  await bloquearWhatsapp(page)
  await entrar(page, 'clienteNovo') // Paulo: já tem a Pipoca (fluxo anterior) e nunca foi atendido no estúdio
  await irPeloMenu(page, 'Agendar', 1280)
  await page.getByRole('heading', { level: 1, name: 'Agendar' }).waitFor()
  await page.getByRole('radio', { name: /^Hidratação/ }).check()
  await page.getByRole('radio', { name: /Pipoca/ }).check()
  await page.locator('.slots .button').first().click()

  const confirmar = page.getByRole('region', { name: 'Confirmar agendamento' })
  await expect(confirmar).toContainText('sinal de R$ 30,00')
  await expect(confirmar).toContainText('Chave Pix: estudio@bemtevi.test')
  await confirmar.getByRole('button', { name: 'Confirmar agendamento' }).click()
  await expect(page.getByText(/Falta o sinal de/)).toContainText('R$ 30,00')
  await expect(page.getByRole('link', { name: 'Enviar comprovante pelo WhatsApp' })).toHaveAttribute('href', /^https:\/\/wa\.me\/55\d+\?text=/)

  await irPeloMenu(page, 'Meus agendamentos', 1280)
  await expect(page.getByText('Sinal pendente: R$ 30,00 por Pix.')).toBeVisible()

  // Estúdio: acha o sinal pendente na Agenda e marca como recebido.
  const estudio = await page.context().newPage()
  await entrar(estudio, 'prestador')
  await irPeloMenu(estudio, 'Agenda', 1280)
  await estudio.getByRole('heading', { level: 1, name: 'Agenda' }).waitFor()
  await acharNaAgenda(estudio, /Sinal pendente · R\$ 30,00/)
  const linha = estudio.locator('.appt', { hasText: 'Pipoca' })
  await expect(linha).toContainText('Sinal pendente')
  await linha.getByRole('button', { name: /^Mais ações de/ }).click() // o sinal também fica atrás de "Mais ações"
  await linha.getByRole('button', { name: /^Sinal recebido de/ }).click()
  await expect(estudio.getByText(/Sinal de .* registrado como recebido\./)).toBeVisible()
  await expect(estudio.locator('.appt', { hasText: 'Pipoca' }).getByText('Sinal recebido', { exact: true })).toBeVisible()

  // Tutor: a tela dele mostra que o sinal foi recebido.
  await irPeloMenu(page, 'Agendar', 1280)
  await page.getByRole('heading', { level: 1, name: 'Agendar' }).waitFor() // espera a tela chegar: a navegação é sob demanda
  await irPeloMenu(page, 'Meus agendamentos', 1280)
  await expect(page.getByText('Sinal recebido.')).toBeVisible()
  await estudio.close()
})

test('estúdio fecha dois dias pelo Expediente e o tutor, em outro navegador, vê o dia fechado no Agendar e não consegue oferecer lista de espera (ADR 0024)', async ({ page }) => {
  const fechado = dia(40)
  const estudio = await page.context().newPage()
  await entrar(estudio, 'prestador')
  await irPeloMenu(estudio, 'Expediente', 1280)
  await estudio.getByRole('heading', { name: 'Folgas e feriados' }).waitFor()
  await expect(estudio.getByText('Folga da equipe')).toBeVisible() // a folga de demonstração já está na lista

  await estudio.getByLabel('Primeiro dia').fill(fechado)
  await estudio.getByLabel('Último dia').fill(dia(41))
  await estudio.getByLabel('Motivo (opcional)').fill('Curso de tosa')
  await estudio.getByRole('button', { name: 'Fechar esses dias' }).click()
  await expect(estudio.getByRole('region', { name: 'Folgas e feriados' }).getByRole('status')).toContainText('fechado.')
  await expect(estudio.getByText('Curso de tosa')).toBeVisible()

  // A Agenda do estúdio avisa no dia fechado.
  await irPeloMenu(estudio, 'Agenda', 1280)
  await estudio.getByRole('heading', { level: 1, name: 'Agenda' }).waitFor()
  await estudio.getByLabel('Data').fill(fechado) // (recarregar a página perderia a sessão, que fica só na memória)
  await expect(estudio.getByText('Estúdio fechado neste dia: Curso de tosa.')).toBeVisible()

  // O tutor escolhe o dia fechado e lê o motivo em vez de uma lista de espera.
  await entrar(page, 'cliente')
  await irPeloMenu(page, 'Agendar', 1280)
  await page.getByRole('radio', { name: /^Banho(?! e tosa)/ }).check()
  await page.getByRole('radio', { name: /Thor/ }).check()
  await page.getByLabel('Data').fill(fechado)
  await expect(page.getByText('O estúdio está fechado neste dia (Curso de tosa). Escolha outro dia.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Avise-me se vagar um horário neste dia' })).toHaveCount(0)
  await estudio.close()
})

test('o dono acha a tutora em Clientes, gera a senha temporária uma vez, e ela entra com ela e é obrigada a criar outra (ADR 0025)', async ({ page, browser }) => {
  await entrar(page, 'prestador')
  await irPeloMenu(page, 'Clientes', 1280)
  await page.getByRole('heading', { level: 1, name: 'Clientes' }).waitFor()
  await page.getByLabel('Buscar cliente').fill('marina')
  const linha = page.getByRole('listitem').filter({ hasText: 'Marina' }).first()
  await expect(linha).toContainText('atendimento')
  await expect(linha).toContainText(/Pets: .*Thor/)

  await linha.getByRole('button', { name: /^Redefinir senha de/ }).click()
  await linha.getByRole('button', { name: 'Gerar senha temporária' }).click()
  const grupo = page.getByRole('group', { name: /^Senha temporária de/ })
  await expect(grupo).toContainText('aparece só agora')
  const senha = (await grupo.locator('code').innerText()).trim()
  expect(senha).toMatch(/^[A-HJ-NP-Za-km-z2-9]{12}$/)
  await grupo.getByRole('button', { name: 'Concluir' }).click()
  await expect(page.getByText(senha)).toHaveCount(0) // a senha some da tela

  // A tutora, em outro navegador: a senha antiga não vale mais, a temporária entra e pede uma nova.
  const tutora = await (await browser.newContext({ baseURL: 'http://127.0.0.1:5173' })).newPage()
  await tutora.goto('/entrar')
  await tutora.getByLabel('E-mail').fill(ACCOUNTS.cliente)
  await tutora.getByLabel('Senha').fill(PASSWORD)
  await tutora.getByRole('button', { name: 'Entrar' }).click()
  await expect(tutora.getByRole('alert')).toBeVisible() // credenciais recusadas

  await tutora.getByLabel('Senha').fill(senha)
  await tutora.getByRole('button', { name: 'Entrar' }).click()
  await expect(tutora.getByRole('heading', { level: 1, name: 'Defina a sua senha' })).toBeVisible()
  await tutora.context().close()
})
