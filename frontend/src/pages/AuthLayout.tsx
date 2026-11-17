import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * Ilustração do painel de entrada: um golden retriever no banho, com o chuveiro, a espuma e a banheira. Desenhada em formas vetoriais
 * (poucos bytes, nítida em qualquer tela) com uma paleta curta e sombreamento em planos. É decoração: escondida de leitores de tela.
 */
function BathIllustration() {
  const foam = (cx: number, cy: number, r: number) => (
    <g key={`${cx}-${cy}`}>
      <circle cx={cx} cy={cy + r * 0.12} r={r} fill="#c9e3ec" />
      <circle cx={cx} cy={cy} r={r} fill="#ffffff" />
      <path d={`M ${cx - r * 0.55} ${cy - r * 0.15} A ${r * 0.6} ${r * 0.6} 0 0 1 ${cx - r * 0.05} ${cy - r * 0.6}`} fill="none" stroke="#e6f3f8" strokeWidth={Math.max(2, r / 7)} strokeLinecap="round" />
    </g>
  )
  const bubbles: [number, number, number][] = [
    [64, 96, 22],
    [112, 52, 11],
    [30, 190, 12],
    [440, 250, 20],
    [412, 330, 10],
    [78, 266, 9],
    [418, 138, 8],
  ]
  const drops: [number, number][] = [
    [352, 118],
    [378, 134],
    [330, 140],
    [364, 160],
    [344, 176],
    [388, 170],
  ]
  return (
    <svg className="pp-auth__art" viewBox="0 0 480 560" role="presentation" aria-hidden="true" focusable="false">
      {/* chuveiro */}
      <path d="M 476 20 L 476 52 Q 420 52 392 78" fill="none" stroke="#7fa7b8" strokeWidth="9" strokeLinecap="round" />
      <g transform="rotate(32 380 92)">
        <ellipse cx="380" cy="92" rx="38" ry="15" fill="#9bbfce" />
        <ellipse cx="380" cy="88" rx="38" ry="13" fill="#bcd8e3" />
        <ellipse cx="380" cy="104" rx="30" ry="6" fill="#7fa7b8" />
      </g>
      {drops.map(([x, y]) => (
        <path key={`${x}-${y}`} d={`M ${x} ${y} q 5 9 0 14 q -5 -5 0 -14 Z`} fill="#7cc4e0" />
      ))}
      {/* bolhas soltas */}
      {bubbles.map(([cx, cy, r]) => (
        <g key={`b${cx}-${cy}`}>
          <circle cx={cx} cy={cy} r={r} fill="#ffffff" fillOpacity="0.4" stroke="#ffffff" strokeWidth="2.5" />
          <path d={`M ${cx - r * 0.55} ${cy - r * 0.1} A ${r * 0.6} ${r * 0.6} 0 0 1 ${cx - r * 0.1} ${cy - r * 0.55}`} fill="none" stroke="#ffffff" strokeWidth={Math.max(2, r / 6)} strokeLinecap="round" />
        </g>
      ))}
      {/* orelhas caídas */}
      <path d="M 150 196 C 104 196 86 262 104 330 C 112 360 150 366 164 336 C 176 300 176 236 168 204 Z" fill="#c8841f" />
      <path d="M 330 196 C 376 196 394 262 376 330 C 368 360 330 366 316 336 C 304 300 304 236 312 204 Z" fill="#c8841f" />
      <path d="M 140 222 C 120 270 124 318 142 340" fill="none" stroke="#dba046" strokeWidth="6" strokeLinecap="round" />
      <path d="M 340 222 C 360 270 356 318 338 340" fill="none" stroke="#dba046" strokeWidth="6" strokeLinecap="round" />
      {/* cabeça */}
      <path d="M 240 140 C 322 140 364 196 364 262 C 364 330 312 372 240 372 C 168 372 116 330 116 262 C 116 196 158 140 240 140 Z" fill="#eaa84a" />
      {/* luz na testa e mancha clara do focinho */}
      <path d="M 240 148 C 276 148 308 168 322 198 C 290 184 262 182 240 184 C 218 182 190 184 158 198 C 172 168 204 148 240 148 Z" fill="#f4c474" />
      <path d="M 226 188 C 230 232 226 262 214 292 L 266 292 C 254 262 250 232 254 188 Z" fill="#f6cf88" />
      <ellipse cx="240" cy="318" rx="68" ry="50" fill="#f9e3b9" />
      {/* sobrancelhas e olhos */}
      <path d="M 176 214 Q 194 204 212 212" fill="none" stroke="#a96a14" strokeWidth="5" strokeLinecap="round" />
      <path d="M 268 212 Q 286 204 304 214" fill="none" stroke="#a96a14" strokeWidth="5" strokeLinecap="round" />
      <ellipse cx="196" cy="246" rx="14" ry="16" fill="#22303b" />
      <ellipse cx="284" cy="246" rx="14" ry="16" fill="#22303b" />
      <circle cx="201" cy="240" r="5" fill="#ffffff" />
      <circle cx="289" cy="240" r="5" fill="#ffffff" />
      <circle cx="192" cy="252" r="2.4" fill="#ffffff" fillOpacity="0.7" />
      <circle cx="280" cy="252" r="2.4" fill="#ffffff" fillOpacity="0.7" />
      {/* nariz, boca e língua */}
      <path d="M 218 292 Q 240 278 262 292 Q 262 312 240 316 Q 218 312 218 292 Z" fill="#22303b" />
      <ellipse cx="231" cy="291" rx="7" ry="3" fill="#ffffff" fillOpacity="0.45" />
      <path d="M 240 316 L 240 330" stroke="#22303b" strokeWidth="4" strokeLinecap="round" />
      <path d="M 206 336 Q 240 372 274 336" fill="#7a3a2b" />
      <path d="M 222 350 Q 240 392 258 350 Q 240 340 222 350 Z" fill="#f28b95" />
      <path d="M 240 350 L 240 372" stroke="#d96f7c" strokeWidth="2.5" strokeLinecap="round" />
      <path d="M 202 332 Q 220 342 240 330 Q 260 342 278 332" fill="none" stroke="#22303b" strokeWidth="4" strokeLinecap="round" />
      {/* espuma na cabeça */}
      {[
        [240, 142, 40],
        [196, 160, 30],
        [284, 160, 30],
        [218, 118, 22],
        [264, 114, 20],
        [160, 188, 17],
        [320, 188, 17],
      ].map(([cx, cy, r]) => foam(cx, cy, r))}
      {/* banheira (mais baixa que a cabeça: a espuma não cobre a boca) */}
      <g transform="translate(0 44)">
      <path d="M 32 396 L 448 396 L 448 452 Q 448 504 396 504 L 84 504 Q 32 504 32 452 Z" fill="#ffffff" />
      <path d="M 32 452 Q 32 504 84 504 L 396 504 Q 448 504 448 452 L 448 440 L 32 440 Z" fill="#cfe6ee" />
      <rect x="20" y="384" width="440" height="26" rx="13" fill="#f2f9fb" />
      <rect x="20" y="398" width="440" height="12" rx="6" fill="#bcd8e3" />
      {/* espuma sobre a borda */}
      {[
        [70, 380, 34],
        [128, 384, 28],
        [190, 376, 38],
        [258, 380, 32],
        [322, 376, 40],
        [394, 382, 32],
        [100, 352, 18],
        [352, 346, 20],
      ].map(([cx, cy, r]) => foam(cx, cy, r))}
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
