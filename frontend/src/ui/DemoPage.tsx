import { useState } from 'react'
import { Button, Choice, Field, Fio, FioItem, Notice, SlotButton, StateBadge, StateDot, Summary } from './components'
import type { AppointmentState } from './states'
import { ThemeToggle } from './ThemeToggle'

/** Página de demonstração da fundação visual (R2). SÓ existe em desenvolvimento: a rota não entra no build de produção (ver App.tsx). */
const SWATCHES: { name: string; token: string }[] = [
  { name: 'Fundo (louça)', token: '--pp-bg' },
  { name: 'Superfície', token: '--pp-surface' },
  { name: 'Texto (tinta)', token: '--pp-text' },
  { name: 'Apoio', token: '--pp-muted' },
  { name: 'Azul de banho', token: '--pp-primary' },
  { name: 'Ouro de coleira', token: '--pp-gold' },
  { name: 'Borda de campo', token: '--pp-field' },
  { name: 'Perigo', token: '--pp-danger' },
]

const FIO: { time: string; pet: string; tutor: string; state: AppointmentState; meta: string }[] = [
  { time: '08:30', pet: 'Thor', tutor: 'Marina Alves', state: 'done', meta: 'Cão, porte médio · Banho e tosa · 1 h 30' },
  { time: '09:30', pet: 'Mel', tutor: 'João Lima', state: 'done', meta: 'Gata, porte pequeno · Banho · 45 min' },
  { time: '10:30', pet: 'Bidu', tutor: 'Carla Souza', state: 'waiting', meta: 'Cão, porte grande · Tosa higiênica · 1 h' },
  { time: '11:30', pet: 'Nina', tutor: 'Marina Alves', state: 'scheduled', meta: 'Cão, porte pequeno · Banho · 45 min' },
  { time: '14:00', pet: 'Pipoca', tutor: 'Paulo Reis', state: 'cancelled', meta: 'Cão, porte médio · Tosa na tesoura · 1 h 30' },
]

export default function DemoPage() {
  const [slot, setSlot] = useState('09:45')
  const [saving, setSaving] = useState(false)
  return (
    <div className="pp" style={{ minHeight: '100vh', padding: 'var(--pp-space-6) max(16px, 4vw) var(--pp-space-12)' }}>
      <main style={{ maxWidth: 760, margin: '0 auto', display: 'grid', gap: 'var(--pp-space-12)' }}>
        <header style={{ display: 'flex', flexWrap: 'wrap', gap: 16, justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div>
            <h1 style={{ font: '700 clamp(1.9rem, 5vw, 2.75rem)/1.05 var(--pp-display)', letterSpacing: '-0.025em', margin: 0 }}>Fundação visual</h1>
            <p style={{ color: 'var(--pp-muted)', margin: '6px 0 0' }}>Pelo &amp; Ponto, só em desenvolvimento. Tokens, fontes, temas e componentes base.</p>
          </div>
          <ThemeToggle />
        </header>

        <section aria-labelledby="cores">
          <h2 id="cores" style={{ font: '700 1.375rem/1.15 var(--pp-display)', margin: '0 0 12px' }}>Cores</h2>
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 12 }}>
            {SWATCHES.map((s) => (
              <li key={s.token} style={{ display: 'grid', gap: 6 }}>
                <span aria-hidden="true" style={{ height: 44, borderRadius: 10, background: `var(${s.token})`, border: '2px solid var(--pp-line)' }} />
                <span style={{ fontSize: '0.875rem' }}>{s.name}</span>
              </li>
            ))}
          </ul>
        </section>

        <section aria-labelledby="tipo">
          <h2 id="tipo" style={{ font: '700 1.375rem/1.15 var(--pp-display)', margin: '0 0 12px' }}>Tipografia</h2>
          <p style={{ font: '700 2.75rem/1.05 var(--pp-display)', margin: 0 }}>10:30 Bidu</p>
          <p style={{ maxWidth: '38rem' }}>Texto corrido em Atkinson Hyperlegible Next. Os algarismos de toda a interface vêm da Bricolage: 1 h 30, 45 min, 08:00, 20/11/2026.</p>
        </section>

        <section aria-labelledby="botoes">
          <h2 id="botoes" style={{ font: '700 1.375rem/1.15 var(--pp-display)', margin: '0 0 12px' }}>Botões</h2>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            <Button variant="primary">Marcar chegada</Button>
            <Button variant="gold">Começar atendimento</Button>
            <Button>Voltar</Button>
            <Button variant="quiet">Remarcar</Button>
            <Button variant="danger">Cancelar</Button>
            <Button
              variant="primary"
              loading={saving}
              onClick={() => {
                setSaving(true)
                setTimeout(() => setSaving(false), 1500)
              }}
            >
              Agendar
            </Button>
          </div>
        </section>

        <section aria-labelledby="campos">
          <h2 id="campos" style={{ font: '700 1.375rem/1.15 var(--pp-display)', margin: '0 0 12px' }}>Campos, opções e horários</h2>
          <div style={{ display: 'grid', gap: 20, maxWidth: 520 }}>
            <Field label="Nome do pet" hint="Como o tutor chama o pet.">
              {(c) => <input className="pp-inp" {...c} defaultValue="Thor" />}
            </Field>
            <Field label="Porte" error="Escolha o porte do pet.">
              {(c) => (
                <select className="pp-inp" {...c} defaultValue="">
                  <option value="">Escolha o porte</option>
                  <option>Pequeno</option>
                  <option>Médio</option>
                </select>
              )}
            </Field>
            <fieldset style={{ border: 0, padding: 0, margin: 0, display: 'grid', gap: 10 }}>
              <legend style={{ fontWeight: 700, padding: 0, marginBottom: 6 }}>Serviço</legend>
              <Choice type="radio" name="servico" label="Banho" detail="45 min" defaultChecked />
              <Choice type="radio" name="servico" label="Banho e tosa" detail="1 h 30" />
            </fieldset>
            <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
              <legend style={{ fontWeight: 700, padding: 0, marginBottom: 6 }}>Horário livre</legend>
              <div className="pp-slots">
                <SlotButton time="09:00" unavailable />
                {['09:45', '10:30', '13:30'].map((t) => (
                  <SlotButton key={t} time={t} selected={slot === t} onSelect={() => setSlot(t)} />
                ))}
              </div>
            </fieldset>
          </div>
        </section>

        <section aria-labelledby="estados">
          <h2 id="estados" style={{ font: '700 1.375rem/1.15 var(--pp-display)', margin: '0 0 12px' }}>Estados e avisos</h2>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center', marginBottom: 16 }}>
            {(['scheduled', 'waiting', 'done', 'cancelled'] as const).map((s) => (
              <span key={s} style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                <StateDot state={s} />
                <StateBadge state={s} />
              </span>
            ))}
          </div>
          <div style={{ display: 'grid', gap: 10 }}>
            <Notice>Os horários de sábado aparecem a partir de segunda.</Notice>
            <Notice kind="success">Agendamento marcado para 18/11 às 09:45.</Notice>
            <Notice kind="error">Não foi possível agendar: esse horário acabou de ser ocupado. Escolha outro.</Notice>
          </div>
        </section>

        <section aria-labelledby="fio">
          <h2 id="fio" style={{ font: '700 1.375rem/1.15 var(--pp-display)', margin: '0 0 12px' }}>O fio do dia</h2>
          <Summary>
            <strong>2 concluídos</strong>, <strong>1 esperando</strong> e <strong>2 por vir</strong>. O próximo a chegar é a Nina, às 11:30.
          </Summary>
          <div style={{ height: 24 }} />
          <Fio label="Atendimentos de terça">
            {FIO.map((a) => (
              <FioItem key={a.time} time={a.time} state={a.state}>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px 14px', alignItems: 'baseline' }}>
                  <strong style={{ font: '700 1.375rem/1.15 var(--pp-display)' }}>{a.pet}</strong>
                  <span style={{ color: 'var(--pp-muted)' }}>{a.tutor}</span>
                  <StateBadge state={a.state} />
                </div>
                <p style={{ margin: 0, color: 'var(--pp-muted)' }}>{a.meta}</p>
              </FioItem>
            ))}
          </Fio>
        </section>
      </main>
    </div>
  )
}
