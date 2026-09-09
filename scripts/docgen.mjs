/**
 * Thin wrapper around @capacitor/docgen that emits distinct headings/anchors
 * for sendMessage overloads (stock docgen collapses both to #sendmessage).
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const formatting = require('@capacitor/docgen/dist/formatting.js');
const { run } = require('@capacitor/docgen/dist/cli.js');

const originalFormatMethodSignature = formatting.formatMethodSignature;

formatting.formatMethodSignature = (method) => {
  if (method.name === 'sendMessage' && method.parameters.length > 0) {
    const types = method.parameters.map((parameter) => parameter.type.replace(/"/g, "'")).join(', ');
    return `sendMessage(${types})`;
  }
  return originalFormatMethodSignature(method);
};

await run({
  cwd: process.cwd(),
  args: process.argv.slice(2),
});
