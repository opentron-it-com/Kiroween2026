/**
 * Extracts fenced code blocks out of an assistant chat message and turns
 * them into a set of (path, content) file entries that can be zipped and
 * downloaded, reconstructing the project folder structure the agents
 * intended.
 *
 * Two sources of truth for the path, preferred in this order:
 *   1. An explicit "FILE: <relative/path>" marker on the first line inside
 *      the fence (see CoordinatorAgent.buildSpecialistTaskPayload on the
 *      backend, which instructs every code-producing specialist to include
 *      one for real project files).
 *   2. A fallback name derived from which "**X Agent:**" section the block
 *      came from (so files at least land in a backend/, frontend/, tests/,
 *      devops/ folder instead of all dumping into one flat pile) plus the
 *      block's language for the extension.
 */

export interface ExtractedCodeFile {
  path: string;
  content: string;
}

const EXT_BY_LANG: Record<string, string> = {
  java: 'java',
  ts: 'ts', typescript: 'ts',
  tsx: 'tsx',
  js: 'js', javascript: 'js',
  jsx: 'jsx',
  py: 'py', python: 'py',
  go: 'go',
  rs: 'rs', rust: 'rs',
  json: 'json',
  yaml: 'yaml', yml: 'yaml',
  css: 'css', scss: 'scss',
  html: 'html',
  sql: 'sql',
  sh: 'sh', bash: 'sh', shell: 'sh',
  md: 'md', markdown: 'md',
  xml: 'xml',
  kt: 'kt', kotlin: 'kt',
  c: 'c',
  cpp: 'cpp', 'c++': 'cpp',
  cs: 'cs', csharp: 'cs',
  php: 'php',
  rb: 'rb', ruby: 'rb',
  swift: 'swift',
  toml: 'toml',
  properties: 'properties',
  ini: 'ini',
  graphql: 'graphql',
  proto: 'proto',
  dockerfile: 'Dockerfile',
};

const AGENT_FOLDER: Record<string, string> = {
  backend: 'backend',
  frontend: 'frontend',
  qa: 'tests',
  devops: 'devops',
  knowledge: 'notes',
};

// First line inside a fence, optionally comment-wrapped, declaring the
// file's intended path. Accepts "FILE: x", "// FILE: x", "# FILE: x",
// "-- FILE: x", "<!-- FILE: x -->".
const FILE_MARKER_RE = /^(?:\/\/|#|--|<!--)?\s*FILE:\s*(.+?)\s*(?:-->)?\s*$/i;

function extFor(lang: string): string {
  return EXT_BY_LANG[(lang || '').toLowerCase()] || 'txt';
}

function slugifyAgentName(name: string): string {
  const key = name.trim().toLowerCase();
  return AGENT_FOLDER[key] || key.replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || 'misc';
}

function splitAgentSections(text: string): { name: string; text: string }[] {
  const regex = /\*\*([^*]+?) Agent:\*\*\s*\n?([\s\S]*?)(?=(\n\n\*\*[^*]+? Agent:\*\*|$))/g;
  const parts: { name: string; text: string }[] = [];
  let m: RegExpExecArray | null;
  while ((m = regex.exec(text)) !== null) {
    const name = (m[1] || '').trim();
    const body = (m[2] || '').trim();
    if (name) parts.push({ name, text: body });
  }
  return parts;
}

function dedupePath(path: string, used: Set<string>): string {
  if (!used.has(path)) {
    used.add(path);
    return path;
  }
  const dotIdx = path.lastIndexOf('.');
  const base = dotIdx > -1 ? path.slice(0, dotIdx) : path;
  const ext = dotIdx > -1 ? path.slice(dotIdx) : '';
  let i = 1;
  let candidate = `${base}-${i}${ext}`;
  while (used.has(candidate)) {
    i++;
    candidate = `${base}-${i}${ext}`;
  }
  used.add(candidate);
  return candidate;
}

/**
 * Strips a "FILE: <path>" marker line from the *rendered* markdown so the
 * chat bubble doesn't show it as a stray first line of code, while leaving
 * it intact in whatever raw text is passed to extractCodeFiles.
 */
export function stripFileMarkersForDisplay(markdown: string): string {
  if (!markdown) return markdown;
  return markdown.replace(/(```[\w+-]*[^\n]*\n)(?:(?:\/\/|#|--|<!--)?\s*FILE:\s*[^\n]+\n)/g, '$1');
}

export function extractCodeFiles(markdown: string): ExtractedCodeFile[] {
  const text = markdown || '';
  const sections = splitAgentSections(text);
  const chunks = sections.length > 0 ? sections : [{ name: '', text }];

  const files: ExtractedCodeFile[] = [];
  const usedPaths = new Set<string>();
  const fenceRe = /```([\w+-]*)[^\n]*\n([\s\S]*?)```/g;

  for (const chunk of chunks) {
    const folder = chunk.name ? slugifyAgentName(chunk.name) : '';
    let blockIndex = 0;
    const re = new RegExp(fenceRe);
    let match: RegExpExecArray | null;
    while ((match = re.exec(chunk.text)) !== null) {
      const lang = match[1] || '';
      let body = match[2] ?? '';
      blockIndex++;

      const lines = body.split('\n');
      let explicitPath: string | null = null;
      if (lines.length > 0) {
        const markerMatch = FILE_MARKER_RE.exec(lines[0].trim());
        if (markerMatch) {
          explicitPath = markerMatch[1].trim();
          lines.shift();
          body = lines.join('\n');
        }
      }
      body = body.replace(/\n$/, '');
      if (!body.trim()) continue;

      let filePath = explicitPath
        ? explicitPath.replace(/^\/+/, '').replace(/\\/g, '/').replace(/\.\.\//g, '')
        : null;
      if (!filePath) {
        const base = `snippet-${blockIndex}.${extFor(lang)}`;
        filePath = folder ? `${folder}/${base}` : base;
      }

      files.push({ path: dedupePath(filePath, usedPaths), content: body });
    }
  }
  return files;
}
