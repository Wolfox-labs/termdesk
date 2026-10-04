import fs from 'node:fs';
import { createRelay } from './relay.js';
const file = process.env.TERMDESK_RELAY_CONFIG;
if (!file) throw new Error('TERMDESK_RELAY_CONFIG is required');
const config = JSON.parse(fs.readFileSync(file, 'utf8'));
const relay = createRelay({ config, configFile: file });
relay.server.listen(config.port || 7421, config.host || '127.0.0.1', () => console.log('TermDesk VPS relay ready on loopback'));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await relay.close(); process.exit(0); });
