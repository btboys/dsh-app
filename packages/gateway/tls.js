import { execFile } from 'node:child_process'
import { chmod, mkdir, readFile, rename, writeFile, unlink } from 'node:fs/promises'
import { networkInterfaces } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { X509Certificate, createPrivateKey, createPublicKey, generateKeyPairSync } from 'node:crypto'

const exec = promisify(execFile)

/** IPv4 addresses this computer can be reached at on the local network. */
export function lanAddresses() {
  const addresses = []
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.internal || entry.family !== 'IPv4') continue
      addresses.push(entry.address)
    }
  }
  return [...new Set(addresses)]
}

/**
 * Loads the gateway identity, creating it once when no identity exists.
 *
 * A half-written identity (key without certificate, or the reverse) fails closed:
 * regenerating would silently change the fingerprint every paired phone trusts.
 */
export async function loadOrCreateCertificate(dir, { hosts = lanAddresses() } = {}) {
  await mkdir(dir, { recursive: true, mode: 0o700 })
  await chmod(dir, 0o700)
  const keyPath = join(dir, 'key.pem')
  const certPath = join(dir, 'cert.pem')
  const key = await readOptional(keyPath)
  const cert = await readOptional(certPath)
  if (key && cert) {
    validateIdentity(key, cert, dir)
    return { key, cert }
  }
  if (key || cert) {
    throw new Error(`incomplete gateway identity in ${dir}: key and certificate must both exist`)
  }
  return createCertificate(dir, { hosts, keyPath, certPath })
}

/** Rejects an identity that cannot serve TLS, so a broken identity fails at startup. */
function validateIdentity(key, cert, dir) {
  let parsed
  try {
    parsed = new X509Certificate(cert)
  } catch {
    throw new Error(`unreadable gateway certificate in ${dir}: delete ${dir} to pair again`)
  }
  if (parsed.validTo && new Date(parsed.validTo) <= new Date()) {
    throw new Error(`expired gateway certificate in ${dir}: delete ${dir} and pair again`)
  }
  let publicKey
  try {
    publicKey = createPublicKey(createPrivateKey(key))
  } catch {
    throw new Error(`unreadable gateway private key in ${dir}: delete ${dir} to pair again`)
  }
  const fromKey = publicKey.export({ type: 'spki', format: 'der' })
  const fromCert = parsed.publicKey.export({ type: 'spki', format: 'der' })
  if (!fromKey.equals(fromCert)) {
    throw new Error(`gateway key does not match its certificate in ${dir}: delete ${dir} and pair again`)
  }
}

async function readOptional(path) {
  try {
    return await readFile(path)
  } catch (error) {
    if (error.code === 'ENOENT') return undefined
    throw error
  }
}

async function createCertificate(dir, { hosts, keyPath, certPath }) {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const key = privateKey.export({ type: 'pkcs8', format: 'pem' })
  const configPath = join(dir, 'openssl.cnf')
  const stagingKeyPath = join(dir, 'key.pem.new')
  const stagingCertPath = join(dir, 'cert.pem.new')
  try {
    await writeFile(stagingKeyPath, key, { mode: 0o600 })
    await writeFile(configPath, opensslConfig(hosts), { mode: 0o600 })
    await exec('openssl', [
      'req', '-new', '-x509',
      '-key', stagingKeyPath,
      '-out', stagingCertPath,
      '-days', '825',
      '-config', configPath,
    ])
    // Rename the certificate first: a crash leaves a cert without a key, which fails
    // closed above instead of serving a regenerated identity.
    await rename(stagingCertPath, certPath)
    await rename(stagingKeyPath, keyPath)
    return { key, cert: await readFile(certPath) }
  } finally {
    await Promise.all([unlink(stagingKeyPath).catch(() => {}), unlink(stagingCertPath).catch(() => {})])
  }
}

export function opensslConfig(hosts) {
  const addresses = ['127.0.0.1', ...hosts].filter((value, index, all) => all.indexOf(value) === index)
  const san = ['DNS:localhost', ...addresses.map(address => `IP:${address}`)].join(',')
  return `
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = dsh-app
[ext]
subjectAltName = ${san}
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
`.trim()
}
