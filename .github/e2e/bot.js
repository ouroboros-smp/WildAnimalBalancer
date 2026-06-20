// Mineflayer E2E: connect to the Folia server, wait for the balancer's cycles,
// and assert that wild food animals actually appear near the player.
// Exits 0 on success, 1 on failure. Used by .github/workflows/integration.yml.

const mineflayer = require('mineflayer');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const VERSION = process.env.MC_VERSION || false; // false = auto-detect from the server
const NEAR = 60;                  // blocks; e2e config spawns within scan-radius 48
const TIMEOUT_MS = 120000;        // plenty of balancer cycles (cycle-seconds=5)
const WILD = new Set(['cow', 'pig', 'sheep', 'chicken']);

const bot = mineflayer.createBot({
  host: HOST,
  port: PORT,
  username: 'WabTester',
  auth: 'offline',
  version: VERSION,
});

let finished = false;
function finish(ok, msg) {
  if (finished) return;
  finished = true;
  console.log(msg);
  try { bot.quit(); } catch (_) {}
  setTimeout(() => process.exit(ok ? 0 : 1), 500);
}

bot.once('spawn', () => {
  console.log('Bot spawned at', bot.entity.position);
  let ticks = 0;
  const timer = setInterval(() => {
    const me = bot.entity && bot.entity.position;
    if (!me) return;
    const entities = Object.values(bot.entities);
    if (ticks === 0) {
      const census = {};
      for (const e of entities) { const n = String(e.name || '?'); census[n] = (census[n] || 0) + 1; }
      console.log('entity census:', JSON.stringify(census));
    }
    ticks++;
    const near = entities.filter(e =>
      e && e.position &&
      WILD.has(String(e.name || '').toLowerCase()) &&
      e.position.distanceTo(me) <= NEAR
    );
    console.log(`[t=${ticks * 5}s] wild animals within ${NEAR} blocks: ${near.length}`);
    if (near.length > 0) {
      clearInterval(timer);
      finish(true, `PASS: WildAnimalBalancer spawned ${near.length} wild animals near the player on Folia`);
    }
  }, 5000);
});

bot.on('kicked', (reason) => finish(false, 'FAIL: kicked - ' + JSON.stringify(reason)));
bot.on('error', (err) => finish(false, 'FAIL: error - ' + (err && err.message ? err.message : String(err))));
setTimeout(() => finish(false, 'FAIL: no wild animals appeared near the player within timeout'), TIMEOUT_MS);
