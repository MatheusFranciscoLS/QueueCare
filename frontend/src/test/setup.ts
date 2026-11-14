import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => {
  cleanup()
  // O token só vive em memória (ADR 0008): nenhum teste pode deixar nada no armazenamento do navegador.
  window.localStorage.clear()
  window.sessionStorage.clear()
})
