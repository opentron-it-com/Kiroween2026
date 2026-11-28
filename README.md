# Ghostbook Quickstart

Ghostbook is a haunted personal-organizer workspace built on top of OpenTron's existing
ingestion and retrieval stack. The supported demo path is:

1. Start the Java 21 backend with the embedded H2 profile.
2. Start the Vite frontend dev server.
3. Open `/ghostbook` in the browser.
4. Download one of the synthetic sample archives and its matching question sheet from the page.
5. Import the archive and wait for the indexing status to reach INDEXED.
6. Ask a question from the question sheet — for example: **"What did Mara and I watch at the Lantern Fair?"**
7. Read the answer, then inspect the **Evidence matches** panel below it for the retrieved archive passage and its source filename.

All sample content is synthetic. The evidence panel shows archive search matches returned by the
backend; it does not imply the model used every displayed passage in its answer.

## Requirements

- Java 21
- Maven 3.8 or newer
- Node.js 20 or newer and npm
- An Ollama-compatible model server running locally, **or** a valid `ENGINE_APIKEY` for a
  cloud model (configured in OpenTron settings — never put keys in this repository)

## Run locally

### 1. Build the backend

From the repository root:

```powershell
# Windows PowerShell
$mvn = "C:\path\to\apache-maven\bin\mvn.cmd"   # adjust to your Maven installation
$env:JAVA_HOME = "C:\path\to\jdk-21"            # adjust to your JDK 21 installation
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

& $mvn -f java\opentron-java\pom.xml `
  -pl cjae-spring-boot-starter,backend -am `
  -DskipTests install
```

```bash
# macOS / Linux
mvn -f java/opentron-java/pom.xml \
  -pl cjae-spring-boot-starter,backend -am \
  -DskipTests install
```

### 2. Start the backend

In one terminal, start the embedded H2 profile (no PostgreSQL required):

```powershell
# Windows PowerShell
Set-Location java\opentron-java\backend
& $mvn spring-boot:run `
  "-Dspring-boot.run.profiles=embedded" `
  "-Dspring-boot.run.jvmArguments=--enable-preview --enable-native-access=ALL-UNNAMED" `
  "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8000"
```

```bash
# macOS / Linux
cd java/opentron-java/backend
mvn spring-boot:run \
  -Dspring-boot.run.profiles=embedded \
  "-Dspring-boot.run.jvmArguments=--enable-preview --enable-native-access=ALL-UNNAMED" \
  "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8000"
```

Confirm the backend is healthy:

```
curl http://127.0.0.1:8000/actuator/health
# expected: {"status":"UP"}
```

### 3. Start the frontend

In a second terminal:

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1
```

Open `http://127.0.0.1:5173/ghostbook`.

The Vite dev server proxies all `/v1` requests to `http://localhost:8000`. To override the
backend URL set `VITE_API_URL` before starting:

```bash
VITE_API_URL=http://127.0.0.1:8000 npm run dev -- --host 127.0.0.1
```

### 4. Configure a model

Before asking questions, open **Settings** in the sidebar and configure a model. The embedded
profile uses Ollama by default (`http://127.0.0.1:11434`). Never paste API keys into sample
files or the repository.

## Demo flow

| Step | Expected result |
|---|---|
| Select a `.csv`, `.pdf`, `.docx`, `.md`, or `.txt` file | File appears in the selection list |
| Select an unsupported extension (e.g. `.jpg`) | Rejected immediately with an error; no upload sent |
| Click **Import** | Status changes to QUEUED then RUNNING then INDEXED |
| Ask a question from the matching question sheet | Answer appears; Evidence matches panel shows the archive passage with the source filename |
| Ask with no archive imported | Answer may come from model knowledge; evidence panel shows no matches |
| Stop the backend then try to import | Explicit connection error — no fabricated success state |

## Run the tests

**Frontend (Vitest):**

```bash
cd frontend
npm test -- --passWithNoTests src/features/ghostbook
```

Expected: 15 tests pass.

**Backend (Maven):**

```powershell
& $mvn -f java\opentron-java\backend\pom.xml test `
  "-Dtest=IngestServiceTest,ConnectorsControllerIntegrationTest,MemoryServiceTest" `
  "-DfailIfNoTests=false" --no-transfer-progress
```

Expected: 10 tests pass (IngestServiceTest 3, MemoryServiceTest 2,
ConnectorsControllerIntegrationTest 5).

## Privacy notes

- The embedded H2 database is stored at `~/.opentron/opentron.mv.db`. It is not shared.
- The backend is bound to loopback (`127.0.0.1`). Do not expose port 8000 publicly.
- Review your configured model route in OpenTron settings before importing files you consider
  sensitive. A local Ollama model keeps inference on-device; a cloud API key routes prompts
  and archive excerpts to that provider.
- Archive contents, prompts, and credentials are not written to application logs or sample files.

## Kiro build notes

The `.kiro/` directory at the repository root contains the Ghostbook feature spec
(`specs/ghostbook-revival/`), steering documents, and the focused test hook
(`hooks/ghostbook-tests.json`). The hook runs `npm test -- src/features/ghostbook`
automatically after Kiro edits any Ghostbook TypeScript file.

The `DEVPOST.md` at the repository root is the submission write-up with concrete Kiro usage
evidence. 


---
# Ghostbook — Kiroween 2026 Devpost Write-up

## Submission links and readiness

- **Working app/demo URL:** Installer available in AWS: https://opentron-msix.s3.eu-north-1.amazonaws.com/GhostBook-2.0.2.0-x64.msix. Run http://127.0.0.1:5173/ghostbook after installation is completed and backend is connected.
- **Demo video:** https://www.youtube.com/watch?v=swZ3uyxcJdk (public YouTube watch page; ~1 min).
- **Public source repository:** https://github.com/opentron-it-com/Kiroween2026 (public github repo)
  Its GitHub license metadata is MIT.

## What it does

Ghostbook revives the personal digital organizer. You import exported notes, journals, and
records (CSV, PDF, DOCX, Markdown, plain text) into a haunted PDA-inspired workspace. The
OpenTron backend extracts and indexes them. You then ask the assistant natural-language
questions, and the workspace shows you both the answer and the specific archive passages that
match your question — with a clear disclaimer that displayed passages are search matches, not
a claim the model cited each one.

---

## 🏗️ Powered by OpenTron backend

## ⚡ BYOK Hybrid Mode: Frontier Reasoning Without IP Leaks or SaaS Markups

> **Frontier AI reasoning without leaking your IP—or paying SaaS token markups.**

Most AI developer tools force a bad trade-off: send sensitive code and business logic to third-party cloud servers at a steep markup, or settle for small local models that stall on complex engineering tasks. **JVMAI** (powered by the **OpenTron Java 21 runtime**) eliminates that compromise with **BYOK Hybrid Mode**:

- 🔑 **Bring Your Own Keys (Zero Markup, Zero Lock-In):** Plug in your own **Anthropic** and **Google Gemini** API keys directly. Keys are encrypted at rest on your machine—never routed through a middleman proxy.
- 🛡️ **CJAE Confidential Scrubbing Before Cloud Egress:** Before any complex prompt escalates to a cloud model, the **Confidential AI Engine (CJAE)** scans, redacts sensitive identifiers and secrets, and enforces workspace boundary rules—keeping raw business logic and proprietary alpha on your device.
- 💸 **Prompt-Cached Cost Savings + Local Offloading (Up to 80–90% Lower Spend):** Routine classification, triage, and straightforward coding run free on preloaded local Ollama models (`Qwen3:4b`, `Qwen2.5-Coder:7b`, `DeepSeek-R1:8b`, `Gemma3:4b`). Only `COMPLEX` and `EXTREME` tasks escalate to frontier cloud models—using **prompt caching** and **bounded single-pass execution** (no runaway agent retry loops).

---

### 🔄 How a Request Flows in BYOK Hybrid Mode

```text
[Prompt] ──▶ 1. Local Triage (Qwen3:4b @ $0)
                 ├── SIMPLE ───────────▶ 2. Local Ollama Execution (100% On-Device, $0 Token Cost)
                 └── COMPLEX / EXTREME ─▶ 3. CJAE Scrubbing & Redaction (Secrets & Identifiers Stripped)
                                              └──▶ 4. Prompt-Cached Single-Pass Cloud Call (Anthropic / Gemini)
                                                        └──▶ 5. Local AES-256-GCM Telemetry & Cost Audit Log
```

Open Tron replaces fragile, unoptimized prototyping scripts with a deterministic, decoupled, and highly concurrent AI agent runtime.

---
