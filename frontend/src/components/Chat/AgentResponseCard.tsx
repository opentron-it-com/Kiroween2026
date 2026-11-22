import { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import rehypeHighlight from 'rehype-highlight';
import rehypeKatex from 'rehype-katex';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { stripFileMarkersForDisplay } from '../../lib/codeArchive';
import type { ChatMessage } from '../../types';

interface AgentResponseCardProps {
  message: ChatMessage;
  isThinking?: boolean;
}

function CodeBlockPre({ children, ...props }: any) {
  const codeElement = Array.isArray(children) ? children[0] : children;
  const className = codeElement?.props?.className || '';
  const match = /language-([\w-]+)/.exec(className);
  const lang = match ? match[1] : '';

  return (
    <div
      className="code-block-wrapper relative my-3"
      style={{ borderRadius: 'var(--radius-md)', overflow: 'hidden' }}
    >
      <div
        className="flex items-center justify-between px-4 py-1.5 text-xs"
        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-tertiary)' }}
      >
        <span className="font-mono">{lang || 'code'}</span>
      </div>
      <pre {...props} style={{ margin: 0, borderRadius: 0 }}>
        {children}
      </pre>
    </div>
  );
}

function getAgentName(content: string): string {
  // Extract agent name from formats like:
  // "🤔 Backend Agent is thinking..."
  // "✓ Backend Agent\n\n[response]"
  // "❌ Backend Agent\n\n[error]"
  const match = content.match(/(?:🤔|💭|✓|❌)\s+(\w+)\s+(?:Agent|agent)/);
  return match ? match[1] : 'Agent';
}

function isThinkingMessage(content: string): boolean {
  // Anchored to the actual live placeholder format built in InputArea.tsx
  // ("🤔 {Agent} agent is thinking (model)...[steps]") - a naive
  // content.includes('thinking') substring search matches ANY finished
  // response whose own answer text happens to mention the word "thinking"
  // anywhere (e.g. a Frontend specialist discussing a loading/"thinking"
  // UI indicator - not unlikely given this project's own chat UI uses
  // exactly that wording). That misclassifies a fully-arrived, populated
  // answer as still in progress, permanently stuck showing this card's
  // pulsing "Thinking..." skeleton instead of the real content that's
  // already sitting in message.content. Only the live placeholder ever
  // starts with this exact prefix; a finished message starts with "✓ " or
  // "❌ " instead (see getAgentName's/isAgentResponse's own prefix
  // patterns), so anchoring here is safe and precise.
  return /^🤔\s+\S+\s+agent is thinking/i.test(content.trim());
}

function extractResponseContent(content: string): string {
  // If content starts with emoji + Agent, extract the response part
  // Format: "✓ Backend Agent\n\n[response content]"
  const match = content.match(/^[🤔💭✓❌]\s+\w+\s+Agent\s*\n\n([\s\S]*)$/);
  return match ? match[1] : content;
}

function sanitizeMarkdown(markdown: string): string {
  // Fix malformed code blocks: standalone language identifiers before ```
  // e.g., "java\n```" -> "```java"
  markdown = markdown.replace(/^(java|javascript|typescript|python|java|csharp|go|rust|cpp|c|sql|html|css|xml|json|yaml|bash|shell|sh)\n(```)/gm, '```$1');
  
  // Fix language identifiers followed by backticks without newline
  // e.g., "java```" -> "```java"
  markdown = markdown.replace(/^(java|javascript|typescript|python|csharp|go|rust|cpp|c|sql|html|css|xml|json|yaml|bash|shell|sh)```/gm, '```$1');
  
  // Remove stray language keywords that appear before code blocks
  markdown = markdown.replace(/^(less|markdown)\n(```)/gm, '```');
  
  // Fix typos in annotations and keywords
  markdown = markdown.replace(/@Cacheab\s*le/g, '@Cacheable');
  markdown = markdown.replace(/\.leng\s*th\(/g, '.length(');
  markdown = markdown.replace(/O\(\s*1\)/g, 'O(1)');
  
  // Fix bold formatting issues
  markdown = markdown.replace(/\*\s+\*/g, '**');

  // Close a trailing unterminated code fence. A response that got cut off
  // mid-generation (hit the model's token limit, dropped connection, etc.)
  // can end with an odd number of ``` fences - every line after that point
  // then renders INSIDE the still-open <pre> block instead of as normal
  // markdown below it, which is what made a stray trailing line (e.g. cost/
  // time/token stats) look "glued" onto truncated code. Count fence markers
  // at line starts and append a closing ``` when the count is odd so the
  // rest of the content renders normally.
  const fenceCount = (markdown.match(/^```/gm) || []).length;
  if (fenceCount % 2 !== 0) {
    markdown = markdown.replace(/\s*$/, '') + '\n```';
  }

  return markdown;
}

export function AgentResponseCard({ message, isThinking }: AgentResponseCardProps) {
  const agentName = useMemo(() => getAgentName(message.content), [message.content]);
  const thinking = useMemo(() => isThinking !== undefined ? isThinking : isThinkingMessage(message.content), [message.content, isThinking]);
  const responseContent = useMemo(
    () => stripFileMarkersForDisplay(sanitizeMarkdown(extractResponseContent(message.content))),
    [message.content]
  );

  if (thinking) {
    // Thinking state - simple loading indicator
    return (
      <div className="mb-3 rounded-lg p-3 border animate-pulse" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center justify-between">
          <div style={{ fontSize: '14px', fontWeight: 600 }}>
            🤔 {agentName} Agent
          </div>
        </div>
        <div style={{ fontSize: '13px', color: 'var(--color-text-tertiary)', marginTop: '8px' }}>
          Thinking...
        </div>
      </div>
    );
  }

  // Full response state
  return (
    <div className="mb-3 rounded-lg p-4 border" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="flex items-center justify-between mb-3">
        <div style={{ fontSize: '13px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.5px', color: 'var(--color-text-secondary)' }}>
          ✓ {agentName} Agent Response
        </div>
      </div>
      <div className="prose max-w-none text-sm">
        <ReactMarkdown
          remarkPlugins={[remarkGfm, remarkMath]}
          rehypePlugins={[[rehypeHighlight, { detect: true }], rehypeKatex]}
          components={{ pre: CodeBlockPre }}
        >
          {responseContent}
        </ReactMarkdown>
      </div>
    </div>
  );
}
