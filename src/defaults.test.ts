import assert from 'node:assert/strict';
import test from 'node:test';

import { DEFAULT_WATCH_CAPABILITY } from './definitions.js';

test('DEFAULT_WATCH_CAPABILITY defaults to capgo_watch', () => {
  assert.equal(DEFAULT_WATCH_CAPABILITY, 'capgo_watch');
});

test('SendMessageOptions expectsReply is optional', () => {
  const options: { data: { ping: boolean }; expectsReply?: boolean } = { data: { ping: true } };
  assert.equal(options.expectsReply, undefined);
});

test('getReceivedState shape uses context key', () => {
  const state = { context: { theme: 'dark' } };
  assert.deepEqual(state.context, { theme: 'dark' });
});
