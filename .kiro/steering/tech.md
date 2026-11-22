---
inclusion: always
---

# Technology and implementation constraints

- Preserve the existing React 19, TypeScript, Vite, and Tauri architecture in `frontend/`; the backend is Java/Spring Boot.
- Reuse existing upload, chat, and retrieval APIs after checking their actual request and response contracts. Do not duplicate ingestion or agent execution in the UI.
- Keep types explicit, surface errors in the established UI pattern, and avoid logging prompts, archive contents, credentials, or personal records.
- Keep frontend changes scoped to Ghostbook and preserve existing routes and behavior.
- Validate changes with the smallest relevant Vitest selection and `npm run build` from `frontend/`. Run backend tests only when backend code changes.
- Keep `.kiro/` tracked. The repository's current proprietary license is not sufficient for submission; do not publish or change licensing scope without rights-holder approval.
