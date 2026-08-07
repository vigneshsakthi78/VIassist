import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const apiBaseUrl = (process.env.API_BASE_URL || '').replace(/\/$/, '');

const content = `export const environment = {
  production: true,
  apiBaseUrl: '${apiBaseUrl.replace(/'/g, "\\'")}',
};
`;

const target = join(__dirname, '..', 'src', 'environments', 'environment.prod.ts');
writeFileSync(target, content, 'utf8');
console.log(`Wrote environment.prod.ts with apiBaseUrl='${apiBaseUrl || '(empty)'}'`);
