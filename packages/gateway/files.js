import { realpath } from 'node:fs/promises'
import { relative, resolve, isAbsolute, sep } from 'node:path'

const IMAGE_TYPES = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
}

export function safeChild(root, requested) {
  if (typeof requested !== 'string' || requested.startsWith('/') || requested.includes('\0')) return undefined
  const base = resolve(root)
  const target = resolve(base, requested)
  const path = relative(base, target)
  if (path === '..' || path.startsWith(`..${sep}`) || isAbsolute(path)) return undefined
  return target
}

// Checks an existing path's resolved location; callers must account for filesystem races.
export async function safeRealChild(root, requested) {
  const target = safeChild(root, requested)
  if (!target) return undefined
  try {
    const [base, child] = await Promise.all([realpath(root), realpath(target)])
    const path = relative(base, child)
    if (path === '..' || path.startsWith(`..${sep}`) || isAbsolute(path)) return undefined
    return child
  } catch (error) {
    if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return undefined
    throw error
  }
}

export function previewFile({ path, bytes, limit }) {
  const extension = extensionOf(path)
  const mediaType = IMAGE_TYPES[extension]
  if (mediaType) {
    return { kind: 'image', path, mediaType, bytes: bytes.length }
  }
  if (!isText(bytes)) return { kind: 'unsupported', path }
  const text = bytes.subarray(0, limit).toString('utf8')
  return { kind: 'text', path, text, truncated: bytes.length > limit }
}

function extensionOf(path) {
  const dot = path.lastIndexOf('.')
  return dot < 0 ? '' : path.slice(dot).toLowerCase()
}

function isText(bytes) {
  return !bytes.includes(0)
}
