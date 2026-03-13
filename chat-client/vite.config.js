import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';

export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: {
    outDir: resolve(__dirname, '../chat-server/src/main/resources/static'),
    emptyOutDir: false,
    rollupOptions: {
      input: resolve(__dirname, 'index.html'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:9091', changeOrigin: true },
      '/ws': { target: 'ws://localhost:9091', ws: true },
      '/files': { target: 'http://localhost:9091', changeOrigin: true },
    },
  },
});
