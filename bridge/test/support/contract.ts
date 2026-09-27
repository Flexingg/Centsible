import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';

const here = dirname(fileURLToPath(import.meta.url));
const contractDir = join(here, '..', '..', '..', 'contract');
const doc = parse(readFileSync(join(contractDir, 'openapi.yaml'), 'utf8'));

const ajv = new Ajv2020({ strict: false, allErrors: true, validateSchema: false });
addFormats.default(ajv);
ajv.addFormat('int64', { type: 'number', validate: (n: number) => Number.isSafeInteger(n) });
ajv.addSchema(doc, 'openapi');

type Res = { statusCode: number; headers: Record<string, unknown>; body: string };

/**
 * Asserts a response matches the operation's declared status and schema in
 * contract/openapi.yaml. Throws with every schema violation listed.
 */
export function expectContract(method: string, pathTemplate: string, res: Res) {
  const op = doc.paths?.[pathTemplate]?.[method.toLowerCase()];
  if (!op) throw new Error(`${method} ${pathTemplate} is not in the contract`);
  const declared = op.responses?.[String(res.statusCode)];
  if (!declared) {
    throw new Error(`${method} ${pathTemplate} returned undeclared status ${res.statusCode}: ${res.body}`);
  }
  const response = declared.$ref ? resolveRef(declared.$ref) : declared;
  const content = response.content as Record<string, { schema: unknown }> | undefined;
  if (!content) return undefined; // e.g. 204
  const [mediaType, { schema }] = Object.entries(content)[0]!;
  const ct = String(res.headers['content-type'] ?? '');
  if (!ct.startsWith(mediaType)) throw new Error(`Expected ${mediaType}, got ${ct}`);

  const body = JSON.parse(res.body);
  const validate = ajv.compile(rebase(schema) as object);
  if (!validate(body)) {
    const errs = validate.errors!.map((e) => `  ${e.instancePath || '/'} ${e.message}`).join('\n');
    throw new Error(`${method} ${pathTemplate} ${res.statusCode} violates contract:\n${errs}\n${res.body.slice(0, 500)}`);
  }
  return body;
}

/**
 * Records a real response as a fixture for the Android decoder tests. Only when
 * RECORD_FIXTURES=1 (npm run fixtures), because ids and dates change on every run.
 */
export function recordFixture(name: string, body: unknown) {
  if (process.env.RECORD_FIXTURES !== '1') return;
  const dir = join(contractDir, 'fixtures');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, `${name}.json`), JSON.stringify(scrub(body), null, 2) + '\n');
}

function scrub(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(scrub);
  if (v && typeof v === 'object') {
    return Object.fromEntries(
      Object.entries(v).map(([k, x]) => [k, /token|secret/i.test(k) && typeof x === 'string' ? `<${k}>` : scrub(x)]),
    );
  }
  return v;
}

function resolveRef(ref: string) {
  return ref
    .replace(/^#\//, '')
    .split('/')
    .reduce((o: Record<string, unknown>, k) => o[k] as Record<string, unknown>, doc);
}

/** Point local "#/components/..." refs at the registered openapi document. */
function rebase(schema: unknown): unknown {
  return JSON.parse(JSON.stringify(schema).replace(/"\$ref":"#\//g, '"$ref":"openapi#/'));
}
