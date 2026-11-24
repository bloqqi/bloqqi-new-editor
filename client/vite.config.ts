import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// In development (`npm run dev`), the WebSocket is proxied to the Java server
// started with `ant run` (port 8080). Unit tests (`npm test`, Vitest) are the
// src/**/*.test.ts files; the browser tests in tests/ are Playwright's.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  build: {
    outDir: 'dist',
  },
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
});
