import { registerPlugin } from '@capacitor/core';

import type { CapgoWatchPlugin } from './definitions';

const CapgoWatch = registerPlugin<CapgoWatchPlugin>('CapgoWatch', {
  web: () => import('./web').then((m) => new m.CapgoWatchWeb()),
});

export * from './definitions';
export { CapgoWatch };
