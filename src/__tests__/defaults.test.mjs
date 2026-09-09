import assert from 'node:assert/strict';
import test from 'node:test';

import { DEFAULT_WATCH_CAPABILITY } from '../../dist/esm/definitions.js';
import { CapgoWatchWeb } from '../../dist/esm/web.js';

test('DEFAULT_WATCH_CAPABILITY defaults to capgo_watch', () => {
  assert.equal(DEFAULT_WATCH_CAPABILITY, 'capgo_watch');
});

test('CapgoWatchWeb.getReceivedState returns null context on web', async () => {
  const web = new CapgoWatchWeb();
  const state = await web.getReceivedState();
  assert.equal(state.context, null);
});

test('CapgoWatchWeb.sendMessage without expectsReply rejects on web', async () => {
  const web = new CapgoWatchWeb();
  await assert.rejects(() => web.sendMessage({ data: { ping: true } }), /not available on web/);
});
