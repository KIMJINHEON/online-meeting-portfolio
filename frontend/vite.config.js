import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    allowedHosts: ['meeting.example.com',
      'hls.example.com'
    ],

    proxy: {
      '/api': 'http://localhost:8081'
    }
  },
  build: {
    // Multi-page build so `admin.html` is generated in `dist/` (prod deploy friendly).
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        admin: resolve(__dirname, 'admin.html')
      },
      output: {
        // Split rarely-changing vendor code into its own chunk so app-code redeploys
        // don't invalidate the user's cached copy. Critical for users on slow networks
        // (notably iOS Safari on Korean mobile carriers) that stall on first fetch.
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('react') || id.includes('scheduler')) return 'react-vendor';
          if (id.includes('hls.js')) return 'hls-vendor';
          if (id.includes('pdfjs-dist')) return 'pdf-vendor';
          return 'vendor';
        }
      }
    }
  },
});
