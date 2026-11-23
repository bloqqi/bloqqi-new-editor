import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development (`npm run dev`), the WebSocket is proxied to the Java server
// started with `ant run` (port 8080).
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
});
