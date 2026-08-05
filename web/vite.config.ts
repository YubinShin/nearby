import react from "@vitejs/plugin-react";
import * as path from "path";
import { defineConfig } from "vite";

const SEARCH_API = process.env.SEARCH_API ?? "http://localhost:8080";
const ASK_API = process.env.ASK_API ?? "http://localhost:8082";

export default defineConfig({
  base: "/",
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  server: {
    port: 5173,
    fs: {
      allow: [path.resolve(__dirname, "..")],
    },
    proxy: {
      "/api/search": {
        target: SEARCH_API,
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api\/search/, ""),
      },
      "/api/ask": {
        target: ASK_API,
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api\/ask/, ""),
      },
    },
  },
});