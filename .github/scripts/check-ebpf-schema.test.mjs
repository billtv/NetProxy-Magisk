import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { checkEbpfSchema } from './check-ebpf-schema.mjs'

const source = JSON.parse(readFileSync(new URL('../../src/android/app/src/main/assets/sing-box.schema.json', import.meta.url)))
test('校验真正的 eBPF 分支，缺失、重复和旧结构均失败', () => {
  checkEbpfSchema(source)
  for (const mutate of [
    schema => { delete schema.$defs.Inbound },
    schema => { schema.$defs.Inbound.oneOf = [] },
    schema => { schema.$defs.Inbound.oneOf.push(schema.$defs.Inbound.oneOf.find(branch => branch.properties?.type?.const === 'ebpf')) },
    schema => { schema.$defs.Inbound.oneOf.find(branch => branch.properties?.type?.const === 'ebpf').properties.mode = {} },
    schema => { delete schema.$defs.EBPFLocalOptions.properties.data_plane },
    schema => { schema.$defs.Inbound.oneOf.find(branch => branch.properties?.type?.const === 'ebpf').properties.shared.$ref = '#/$defs/Missing' },
  ]) {
    const schema = structuredClone(source)
    mutate(schema)
    assert.throws(() => checkEbpfSchema(schema))
  }
})
