import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * Ilustração do painel de entrada: um cachorro dourado no banho, com o chuveiro, a espuma e a banheira. Formas vetoriais (poucos bytes,
 * nítida em qualquer tela), paleta curta e sombreamento em planos. As cores da água, da espuma e da banheira vêm de variáveis do painel
 * (claro e escuro); o dourado do pelo é o mesmo nos dois temas. É decoração: escondida de leitores de tela.
 */
const water = (name: string) => ({ fill: `var(--ill-${name})` })

function BathIllustration() {
  const foam = (cx: number, cy: number, r: number) => (
    <g key={`${cx}-${cy}`}>
      <circle cx={cx} cy={cy + r * 0.12} r={r} style={water('foam-shade')} />
      <circle cx={cx} cy={cy} r={r} style={water('foam')} />
      <path d={`M ${cx - r * 0.55} ${cy - r * 0.15} A ${r * 0.6} ${r * 0.6} 0 0 1 ${cx - r * 0.05} ${cy - r * 0.6}`} fill="none" style={{ stroke: 'var(--ill-foam-light)' }} strokeWidth={Math.max(2, r / 7)} strokeLinecap="round" />
    </g>
  )
  const bubbles: [number, number, number][] = [
    [70, 110, 20],
    [118, 60, 10],
    [432, 262, 18],
    [84, 268, 8],
  ]
  const drops: [number, number][] = [
    [352, 128],
    [378, 146],
    [330, 150],
    [364, 172],
    [344, 188],
  ]
  return (
    <svg className="pp-auth__art" viewBox="0 0 480 560" role="presentation" aria-hidden="true" focusable="false">
      {/* chuveiro */}
      <path d="M 476 24 L 476 58 Q 424 58 396 84" fill="none" style={{ stroke: 'var(--ill-metal)' }} strokeWidth="9" strokeLinecap="round" />
      <g transform="rotate(32 384 100)">
        <ellipse cx="384" cy="100" rx="38" ry="15" style={water('metal-shade')} />
        <ellipse cx="384" cy="96" rx="38" ry="13" style={water('metal-light')} />
        <ellipse cx="384" cy="112" rx="30" ry="6" style={water('metal')} />
      </g>
      {drops.map(([x, y]) => (
        <path key={`${x}-${y}`} d={`M ${x} ${y} q 5 9 0 14 q -5 -5 0 -14 Z`} style={water('drop')} />
      ))}
      {/* poucas bolhas, só para dar leveza */}
      {bubbles.map(([cx, cy, r]) => (
        <g key={`b${cx}-${cy}`}>
          <circle cx={cx} cy={cy} r={r} fill="none" style={{ stroke: 'var(--ill-bubble)' }} strokeWidth="2.5" />
          <path d={`M ${cx - r * 0.55} ${cy - r * 0.1} A ${r * 0.6} ${r * 0.6} 0 0 1 ${cx - r * 0.1} ${cy - r * 0.55}`} fill="none" style={{ stroke: 'var(--ill-bubble)' }} strokeWidth={Math.max(2, r / 6)} strokeLinecap="round" />
        </g>
      ))}
      {/* o cachorro: proporções mais contidas (cabeça menor, olhos menores) */}
      <g transform="translate(36 70) scale(0.85)">
        <path d="M 150 196 C 104 196 86 262 104 330 C 112 360 150 366 164 336 C 176 300 176 236 168 204 Z" fill="#c8841f" />
        <path d="M 330 196 C 376 196 394 262 376 330 C 368 360 330 366 316 336 C 304 300 304 236 312 204 Z" fill="#c8841f" />
        <path d="M 140 222 C 120 270 124 318 142 340" fill="none" stroke="#dba046" strokeWidth="6" strokeLinecap="round" />
        <path d="M 340 222 C 360 270 356 318 338 340" fill="none" stroke="#dba046" strokeWidth="6" strokeLinecap="round" />
        <path d="M 240 140 C 322 140 364 196 364 262 C 364 330 312 372 240 372 C 168 372 116 330 116 262 C 116 196 158 140 240 140 Z" fill="#eaa84a" />
        <path d="M 240 148 C 276 148 308 168 322 198 C 290 184 262 182 240 184 C 218 182 190 184 158 198 C 172 168 204 148 240 148 Z" fill="#f4c474" />
        <path d="M 228 188 C 232 232 228 262 216 292 L 264 292 C 252 262 248 232 252 188 Z" fill="#f6cf88" />
        <ellipse cx="240" cy="318" rx="64" ry="48" fill="#f9e3b9" />
        <path d="M 178 214 Q 194 206 210 212" fill="none" stroke="#a96a14" strokeWidth="5" strokeLinecap="round" />
        <path d="M 270 212 Q 286 206 302 214" fill="none" stroke="#a96a14" strokeWidth="5" strokeLinecap="round" />
        <ellipse cx="198" cy="246" rx="11" ry="13" fill="#22303b" />
        <ellipse cx="282" cy="246" rx="11" ry="13" fill="#22303b" />
        <circle cx="202" cy="241" r="4" fill="#ffffff" />
        <circle cx="286" cy="241" r="4" fill="#ffffff" />
        <path d="M 220 292 Q 240 280 260 292 Q 260 310 240 314 Q 220 310 220 292 Z" fill="#22303b" />
        <ellipse cx="232" cy="291" rx="6" ry="2.6" fill="#ffffff" fillOpacity="0.4" />
        <path d="M 240 314 L 240 328" stroke="#22303b" strokeWidth="4" strokeLinecap="round" />
        <path d="M 208 336 Q 240 370 272 336" fill="#7a3a2b" />
        <path d="M 224 348 Q 240 388 256 348 Q 240 340 224 348 Z" fill="#f28b95" />
        <path d="M 240 348 L 240 368" stroke="#d96f7c" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M 204 332 Q 222 342 240 328 Q 258 342 276 332" fill="none" stroke="#22303b" strokeWidth="4" strokeLinecap="round" />
        {[
          [240, 142, 38],
          [198, 160, 28],
          [282, 160, 28],
          [220, 120, 20],
          [262, 116, 19],
          [162, 188, 15],
          [318, 188, 15],
        ].map(([cx, cy, r]) => foam(cx, cy, r))}
      </g>
      {/* banheira */}
      <g transform="translate(0 52)">
        <path d="M 32 396 L 448 396 L 448 452 Q 448 504 396 504 L 84 504 Q 32 504 32 452 Z" style={water('tub')} />
        <path d="M 32 452 Q 32 504 84 504 L 396 504 Q 448 504 448 452 L 448 440 L 32 440 Z" style={water('tub-shade')} />
        <rect x="20" y="384" width="440" height="26" rx="13" style={water('rim')} />
        <rect x="20" y="398" width="440" height="12" rx="6" style={water('rim-shade')} />
        {[
          [70, 380, 32],
          [128, 384, 26],
          [190, 376, 36],
          [258, 380, 30],
          [322, 376, 38],
          [394, 382, 30],
          [100, 354, 16],
          [352, 348, 18],
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
