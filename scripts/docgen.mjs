/**
 * Thin wrapper around @capacitor/docgen that emits distinct headings/anchors
 * for sendMessage overloads (stock docgen collapses both to #sendmessage) and
 * includes inherited interface properties in generated docs.
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const formatting = require('@capacitor/docgen/dist/formatting.js');
const generateModule = require('@capacitor/docgen/dist/generate.js');
const parseModule = require('@capacitor/docgen/dist/parse.js');
const outputModule = require('@capacitor/docgen/dist/output.js');
const { run } = require('@capacitor/docgen/dist/cli.js');

if (typeof formatting.formatMethodSignature !== 'function') {
  throw new Error(
    '@capacitor/docgen dist/formatting.js no longer exports formatMethodSignature; ' +
      'update scripts/docgen.mjs before running docgen (private API changed).',
  );
}

const originalFormatMethodSignature = formatting.formatMethodSignature;

formatting.formatMethodSignature = (method, ...rest) => {
  if (method.name === 'sendMessage' && method.parameters.length > 0) {
    const types = method.parameters.map((parameter) => parameter.type.replace(/"/g, "'")).join(', ');
    return `sendMessage(${types})`;
  }
  return originalFormatMethodSignature(method, ...rest);
};

/**
 * Stock docgen only lists own properties. Merge missing properties from known
 * parent interfaces so README sections like SendMessageOptionsWithReply include
 * inherited required fields (e.g. `data`).
 */
const INTERFACE_HERITAGE = {
  SendMessageOptionsWithReply: ['SendMessageOptions'],
};

function mergeInheritedProperties(data) {
  const byName = new Map();
  for (const iface of data.interfaces) {
    byName.set(iface.name, iface);
  }
  if (data.api) {
    byName.set(data.api.name, data.api);
  }

  for (const [childName, parents] of Object.entries(INTERFACE_HERITAGE)) {
    const child = byName.get(childName);
    if (!child) {
      continue;
    }
    const existing = new Set(child.properties.map((p) => p.name));
    const inherited = [];
    for (const parentName of parents) {
      const parent = byName.get(parentName);
      if (!parent) {
        continue;
      }
      for (const prop of parent.properties) {
        if (!existing.has(prop.name)) {
          inherited.push({ ...prop, tags: [...(prop.tags || [])] });
          existing.add(prop.name);
        }
      }
    }
    if (inherited.length > 0) {
      child.properties = [...inherited, ...child.properties];
    }
  }
}

const originalGenerate = generateModule.generate;
generateModule.generate = async (opts) => {
  const apiFinder = parseModule.parse(opts);
  const data = apiFinder(opts.api);
  mergeInheritedProperties(data);
  const results = {
    ...opts,
    data,
  };
  if (opts.outputJsonPath) {
    await outputModule.outputJson(opts.outputJsonPath, data);
  }
  if (opts.outputReadmePath) {
    await outputModule.outputReadme(opts.outputReadmePath, data);
  }
  return results;
};

// Keep reference so bundlers/linters do not drop the patch; cli.run uses generate.
void originalGenerate;

await run({
  cwd: process.cwd(),
  args: process.argv.slice(2),
});
