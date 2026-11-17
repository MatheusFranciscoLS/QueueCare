import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * Ilustração do painel de entrada: um poodle damasco no banho, com espuma na cabeça e bolhas. Desenhada em formas simples (círculos e
 * elipses) para pesar poucos bytes e ficar nítida em qualquer tela. É decoração: escondida de leitores de tela.
 */
function BathIllustration() {
  const bubbles: [number, number, number][] = [
    [62, 70, 26],
    [118, 38, 12],
    [330, 58, 22],
    [372, 128, 12],
    [44, 168, 14],
    [352, 214, 30],
    [26, 262, 20],
    [96, 108, 8],
    [300, 20, 9],
  ]
  return (
    <svg className="pp-auth__art" viewBox="0 0 400 420" role="presentation" aria-hidden="true" focusable="false">
      {/* bolhas soltas */}
      {bubbles.map(([cx, cy, r]) => (
        <g key={`${cx}-${cy}`}>
          <circle cx={cx} cy={cy} r={r} fill="#ffffff" fillOpacity="0.45" stroke="#ffffff" strokeWidth="2.5" />
          <path d={`M ${cx - r * 0.55} ${cy - r * 0.1} A ${r * 0.6} ${r * 0.6} 0 0 1 ${cx - r * 0.1} ${cy - r * 0.55}`} fill="none" stroke="#ffffff" strokeWidth={Math.max(2, r / 6)} strokeLinecap="round" />
        </g>
      ))}
      {/* orelhas */}
      <ellipse cx="112" cy="250" rx="42" ry="74" fill="#e9a93a" />
      <ellipse cx="288" cy="250" rx="42" ry="74" fill="#e9a93a" />
      <circle cx="104" cy="312" r="36" fill="#e9a93a" />
      <circle cx="296" cy="312" r="36" fill="#e9a93a" />
      {/* cabeça */}
      <circle cx="200" cy="236" r="92" fill="#f5c66b" />
      {/* topete de espuma */}
      <g fill="#ffffff" stroke="#cfe6ee" strokeWidth="2">
        <circle cx="200" cy="120" r="40" />
        <circle cx="154" cy="140" r="30" />
        <circle cx="246" cy="140" r="30" />
        <circle cx="176" cy="104" r="22" />
        <circle cx="228" cy="100" r="19" />
        <circle cx="130" cy="168" r="14" />
        <circle cx="272" cy="166" r="15" />
      </g>
      {/* focinho */}
      <ellipse cx="200" cy="278" rx="52" ry="40" fill="#fbe5b6" />
      <ellipse cx="200" cy="262" rx="15" ry="11" fill="#14202b" />
      <path d="M 200 273 L 200 290 M 200 290 Q 188 304 176 296 M 200 290 Q 212 304 224 296" fill="none" stroke="#14202b" strokeWidth="4" strokeLinecap="round" />
      {/* olhos */}
      <circle cx="162" cy="226" r="11" fill="#14202b" />
      <circle cx="238" cy="226" r="11" fill="#14202b" />
      <circle cx="166" cy="222" r="3.5" fill="#ffffff" />
      <circle cx="242" cy="222" r="3.5" fill="#ffffff" />
      {/* língua */}
      <path d="M 192 298 Q 200 328 208 298 Z" fill="#f08a8a" />
      {/* espuma da água na frente */}
      <g fill="#ffffff" stroke="#cfe6ee" strokeWidth="2">
        <circle cx="70" cy="392" r="42" />
        <circle cx="132" cy="400" r="34" />
        <circle cx="200" cy="396" r="46" />
        <circle cx="274" cy="402" r="36" />
        <circle cx="338" cy="392" r="44" />
        <circle cx="100" cy="368" r="22" />
        <circle cx="300" cy="372" r="24" />
      </g>
    </svg>
  )
}

/**
 * Composição das telas de entrada (Entrar, Cadastrar e a página não encontrada de quem não entrou): no computador, um painel de marca
 * com a ilustração do banho ao lado do formulário; no celular, a marca em uma faixa curta acima do formulário.
 */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="pp-auth">
      <aside className="pp-auth__hero">
        <Link to="/" className="pp-brand">
          <span>
            Pelo <b>&amp;</b> Ponto
          </span>
        </Link>
        <p className="pp-auth__headline">Banho e tosa na hora certa.</p>
        <p className="pp-auth__lead">Agenda e fila do seu estúdio em um só lugar. Cada pet bem cuidado, sem fila de espera na porta.</p>
        <BathIllustration />
      </aside>
      <section className="pp-auth__panel">
        <div className="pp-auth__card">{children}</div>
      </section>
    </div>
  )
}
