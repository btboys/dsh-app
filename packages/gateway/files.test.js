import assert from 'node:assert/strict'
import test from 'node:test'
import { previewFile, safeChild } from './files.js'

test('file preview stays inside the workspace and caps text', () => {
  assert.equal(safeChild('/work', 'src/main.js'), '/work/src/main.js')
  assert.equal(safeChild('/work', '../secrets.txt'), undefined)
  assert.equal(safeChild('/work', '/etc/passwd'), undefined)

  const text = previewFile({
    path: '/work/notes.txt',
    bytes: Buffer.from('hello\nworld'),
    limit: 5,
  })
  assert.equal(text.kind, 'text')
  assert.equal(text.text, 'hello')
  assert.equal(text.truncated, true)

  const image = previewFile({
    path: '/work/shot.png',
    bytes: Buffer.from([1, 2, 3]),
    limit: 1024,
  })
  assert.equal(image.kind, 'image')
  assert.equal(image.mediaType, 'image/png')
})

test('binary files are refused instead of being decoded as text', () => {
  const result = previewFile({
    path: '/work/app.bin',
    bytes: Buffer.from([0, 1, 2]),
    limit: 100,
  })
  assert.equal(result.kind, 'unsupported')
})
