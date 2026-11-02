import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

// Development: run the engine with `rocketmaxxing --port 47801 --dev http://localhost:5173`, then `npm run dev`.
const backend = process.env.ROCKETMAXXING_BACKEND ?? "http://127.0.0.1:47801";

export default defineConfig({
  plugins: [svelte()],
  base: "./",
  build: { outDir: process.env.ROCKETMAXXING_OUT ?? "../build/rocketmaxxing-ui", emptyOutDir: true, chunkSizeWarningLimit: 1500 },
  server: { proxy: { "/rpc": { target: backend } } },
});
