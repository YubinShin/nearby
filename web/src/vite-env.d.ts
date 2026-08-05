/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SEARCH_BASE?: string;
  readonly VITE_ASK_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}