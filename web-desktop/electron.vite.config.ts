import { resolve } from 'node:path';
import { defineConfig, externalizeDepsPlugin } from 'electron-vite';
import vue from '@vitejs/plugin-vue';
import type { Plugin } from 'vite';

/**
 * Production CSP is strict (D-92): renderer must not open any outbound
 * connection. In dev the Vite HMR server lives on localhost, so the CSP
 * meta tag is widened only for `electron-vite dev` — the shipped bundle
 * keeps `connect-src 'self'`.
 */
function devCspPlugin(): Plugin {
  return {
    name: 'web-desktop:dev-csp',
    apply: 'serve',
    transformIndexHtml(html) {
      return html.replace(
        /content="default-src 'self';([^"]*)connect-src 'self';([^"]*)"/,
        'content="default-src \'self\';$1connect-src \'self\' ws://localhost:* http://localhost:*;$2"',
      );
    },
  };
}

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    resolve: {
      alias: {
        '@shared': resolve('src/shared'),
      },
    },
    build: {
      outDir: 'out/main',
      rollupOptions: {
        input: {
          index: resolve(__dirname, 'src/main/index.ts'),
        },
        output: {
          entryFileNames: '[name].js',
        },
      },
    },
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    resolve: {
      alias: {
        '@shared': resolve('src/shared'),
      },
    },
    build: {
      outDir: 'out/preload',
      rollupOptions: {
        input: {
          index: resolve(__dirname, 'src/preload/index.ts'),
        },
        output: {
          entryFileNames: '[name].js',
          format: 'cjs',
        },
      },
    },
  },
  renderer: {
    root: resolve(__dirname, 'src/renderer'),
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src/renderer/src'),
        '@shared': resolve(__dirname, 'src/shared'),
      },
    },
    plugins: [vue(), devCspPlugin()],
    build: {
      outDir: 'out/renderer',
      rollupOptions: {
        input: {
          index: resolve(__dirname, 'src/renderer/index.html'),
        },
        output: {
          entryFileNames: 'assets/[name].js',
          chunkFileNames: 'assets/[name]-[hash].js',
          assetFileNames: 'assets/[name]-[hash][extname]',
        },
      },
    },
    server: {
      port: 5173,
    },
  },
});
