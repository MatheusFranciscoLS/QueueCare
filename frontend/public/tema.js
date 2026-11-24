// Tema salvo aplicado ANTES da primeira renderização (sem piscar claro antes do escuro). Sem armazenamento, segue o sistema.
// Fica num ARQUIVO (e não em um <script> dentro do index.html) porque a política de segurança do site (ADR 0038) proíbe script em linha.
try {
  var t = localStorage.getItem('pp-tema')
  if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t)
} catch (e) {}
