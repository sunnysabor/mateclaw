import { defineConfig } from 'vite'
import { resolve } from 'path'

export default defineConfig({
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'HHAIOSWebChat',
      formats: ['es', 'umd'],
      fileName: (format) => `hhaios-webchat.${format}.js`,
    },
    rollupOptions: {
      output: {
        assetFileNames: 'hhaios-webchat.[ext]',
      },
    },
    cssCodeSplit: false,
    minify: 'esbuild',
  },
})
