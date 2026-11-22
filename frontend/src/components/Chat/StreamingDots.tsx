interface Props {
  phase: string;
  agent?: string; // Agent name (e.g., "Backend", "Frontend", "Knowledge")
  model?: string; // Model being used (e.g., "gemma3:4b", "claude-sonnet-5-5", "qwen2.5-coder:7b")
}

export function StreamingDots({ phase, agent, model }: Props) {
  const displayText = agent ? `${agent}${model ? ` (${model})` : ''} thinking` : phase;

  return (
    <div className="flex items-center gap-2 py-2">
      <div className="flex gap-1">
        <span
          className="w-1.5 h-1.5 rounded-full animate-bounce"
          style={{ background: 'var(--color-text-tertiary)', animationDelay: '0ms' }}
        />
        <span
          className="w-1.5 h-1.5 rounded-full animate-bounce"
          style={{ background: 'var(--color-text-tertiary)', animationDelay: '150ms' }}
        />
        <span
          className="w-1.5 h-1.5 rounded-full animate-bounce"
          style={{ background: 'var(--color-text-tertiary)', animationDelay: '300ms' }}
        />
      </div>
      {displayText && (
        <span className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>
          {displayText}
        </span>
      )}
    </div>
  );
}
