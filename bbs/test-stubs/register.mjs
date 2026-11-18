import { register } from 'node:module';
register('hx4s-vite/node-loader', import.meta.url);
register('./cloudflare-workers-loader.mjs', import.meta.url);
