/// <reference types="node" />
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name: string) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? files(path) : [path]
  })
}

describe('código-fonte', () => {
  it('não tem marcadores de conflito do Git esquecidos (o CSS tolera e o build passaria)', () => {
    const marker = /^(<{7}|={7}|>{7})( |$)/m
    const bad = files(join(__dirname, '..')).filter((f) => /\.(css|tsx?|html)$/.test(f) && marker.test(readFileSync(f, 'utf8')))
    expect(bad).toEqual([])
  })
})
