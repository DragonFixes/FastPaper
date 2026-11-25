// Joins viewer bots that stay connected so the server builds and sends chunk/light/block updates for
// the edited areas. Raw clients: they confirm teleports and chunk batches, never send movement.
// usage: node viewers.mjs <port> <name>...   prints "JOINED <name>" per bot, "READY" when all joined.
import { createRequire } from 'node:module'
const require = createRequire(new URL('../bots/package.json', import.meta.url))
const mc = require('minecraft-protocol')

const [port, ...names] = process.argv.slice(2)
let chunks = 0
const join = name => new Promise((resolve, reject) => {
  const c = mc.createClient({ host: '127.0.0.1', port: Number(port), username: name, version: '1.21.11', auth: 'offline' })
  c.on('position', p => c.write('teleport_confirm', { teleportId: p.teleportId }))
  c.on('chunk_batch_finished', () => c.write('chunk_batch_received', { chunksPerTick: 50 }))
  c.on('map_chunk', () => { chunks++; console.log('PKT chunk ' + Date.now()) })
  c.on('update_light', () => console.log('PKT light ' + Date.now()))
  c.on('multi_block_change', () => console.log('PKT blocks ' + Date.now()))
  c.once('position', () => { console.log('JOINED ' + name); resolve(c) })
  c.on('kick_disconnect', r => { console.log('KICKED ' + name + ' ' + JSON.stringify(r)); reject(new Error('kicked')) })
  c.on('error', e => console.log('ERROR ' + name + ' ' + e.message))
  c.on('end', () => console.log('END ' + name))
})
const seen = {}
for (const n of names) { const c = await join(n); c.on('packet', (d, meta) => { if (process.env.PKTALL) { seen[meta.name] = (seen[meta.name] || 0) + 1 } }) }
if (process.env.PKTALL) setInterval(() => console.log('PKTALL ' + JSON.stringify(seen)), 3000)
console.log('READY')
setInterval(() => console.log('CHUNKS ' + chunks), 10000)
