import { WebPlugin } from '@capacitor/core';

import type {
  CapgoWatchPlugin,
  SendMessageOptions,
  UpdateContextOptions,
  TransferUserInfoOptions,
  ReplyMessageOptions,
  WatchInfo,
} from './definitions';

export class CapgoWatchWeb extends WebPlugin implements CapgoWatchPlugin {
  async sendMessage(_options: SendMessageOptions): Promise<void> {
    throw this.unavailable('Apple Watch is not available on web');
  }

  async updateApplicationContext(_options: UpdateContextOptions): Promise<void> {
    throw this.unavailable('Apple Watch is not available on web');
  }

  async transferUserInfo(_options: TransferUserInfoOptions): Promise<void> {
    throw this.unavailable('Apple Watch is not available on web');
  }

  async replyToMessage(_options: ReplyMessageOptions): Promise<void> {
    throw this.unavailable('Apple Watch is not available on web');
  }

  async getInfo(): Promise<WatchInfo> {
    return {
      isSupported: false,
      isPaired: false,
      isWatchAppInstalled: false,
      isReachable: false,
      activationState: 0,
    };
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: 'web' };
  }
}
