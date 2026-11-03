/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SHOWCASE_BASE?: string
  readonly VITE_DEMO_URL?: string
  readonly VITE_SOURCE_URL?: string
  readonly VITE_README_URL?: string
  readonly VITE_RUNBOOK_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
