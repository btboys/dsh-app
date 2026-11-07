import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, mkdir, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { previewFile, safeChild } from './files.js'
import * as files from './files.js'

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


test('real path resolution rejects escaping links and missing targets', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'gateway-files-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const root = join(dir, 'workspace')
  const outside = join(dir, 'workspace-other')
  await mkdir(root)
  await mkdir(outside)
  await mkdir(join(root, 'nested'))
  await writeFile(join(root, 'nested', 'inside.txt'), 'inside')
  await writeFile(join(outside, 'secret.txt'), 'secret')
  await symlink(join(root, 'nested'), join(root, 'inside-link'))
  await symlink(outside, join(root, 'outside-link'))
  await symlink(join(dir, 'missing'), join(root, 'dangling'))
  await symlink(root, join(dir, 'root-link'))
  const canonicalRoot = await realpath(root)
  assert.equal(typeof files.safeRealChild, 'function')
  assert.equal(await files.safeRealChild(root, '.'), canonicalRoot)
  assert.equal(await files.safeRealChild(join(dir, 'root-link'), 'inside-link/inside.txt'), join(canonicalRoot, 'nested', 'inside.txt'))
  assert.equal(await files.safeRealChild(root, 'outside-link/secret.txt'), undefined)
  assert.equal(await files.safeRealChild(root, '../workspace-other/secret.txt'), undefined)
  assert.equal(await files.safeRealChild(root, '/etc/passwd'), undefined)
  assert.equal(await files.safeRealChild(root, 'dangling'), undefined)
  assert.equal(await files.safeRealChild(root, 'missing'), undefined)
  assert.equal(await files.safeRealChild(join(dir, 'missing-root'), '.'), undefined)
  assert.equal(await files.safeRealChild(root, 'bad\0name'), undefined)
  assert.equal(await files.safeRealChild(root, 12), undefined)
})

test('real path resolution supports a filesystem root workspace', async () => {
  const canonicalTmp = await realpath(tmpdir())
  const relativeTmp = canonicalTmp.slice(1)
  assert.equal(await files.safeRealChild('/', relativeTmp), canonicalTmp)
})
