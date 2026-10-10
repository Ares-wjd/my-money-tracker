/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// GitHub Pages 주소: https://ares-wjd.github.io/my-money-tracker/
export default defineConfig({
  base: '/my-money-tracker/',
  plugins: [react()],
  // firebase SDK 가 커서 기본 경고 기준(500kB)을 넘는다.
  build: { chunkSizeWarningLimit: 1000 },
  test: {
    environment: 'node',
  },
});
