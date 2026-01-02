# Example App for `@capgo/capacitor-watch`

This Vite project links directly to the local plugin source so you can exercise the native APIs while developing.

## Actions in this playground

- **Get Watch Info** – Calls getInfo() to discover watch connectivity status.
- **Get Plugin Version** – Gets the native plugin version.
- **Send Message** – Sends an interactive message to the watch. Watch must be reachable.
- **Update Application Context** – Updates the shared application context. Only the latest context is kept.
- **Transfer User Info** – Queues user info for delivery to the watch, even when not reachable.
- **Setup Event Listeners** – Sets up listeners for all watch events.
- **Remove All Listeners** – Removes all event listeners.

## Getting started

```bash
npm install
npm start
```

Add native shells with `npx cap add ios` or `npx cap add android` from this folder to try behaviour on device or simulator.
