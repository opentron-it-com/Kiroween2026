import { DollarSign, TrendingDown, Cloud, HardDrive } from 'lucide-react';
import { useAppStore } from '../../lib/store';

const COMPARISON_PROVIDER_IDS = ['gpt-5.3', 'claude-fable-5-2', 'gemini-4-carbon'];

// Labels only (no prices) for the placeholder shown before real pricing
// data has arrived from the backend -- see comparisonProviders below.
const COMPARISON_PROVIDER_LABELS: Record<string, string> = {
  'gpt-5.3': 'GPT-5.3',
  'claude-fable-5-2': 'Claude Fable 5.2',
  'gemini-4-carbon': 'Gemini 4 Carbon',
};

export function CostComparison() {
  const savings = useAppStore((s) => s.savings);

  if (!savings || savings.total_tokens === 0) {
    return (
      <div className="hud-panel p-6">
        <h3 className="hud-label flex items-center gap-2 mb-4">
          <DollarSign size={12} style={{ color: 'var(--color-success)' }} />
          Cost Comparison
        </h3>
        <div className="h-48 flex items-center justify-center text-sm" style={{ color: 'var(--color-text-tertiary)' }}>
          <span className="hud-mono">awaiting first inference…</span>
        </div>
      </div>
    );
  }

  const providerById = new Map(savings.per_provider.map((p) => [p.provider, p]));
  const comparisonProviders = COMPARISON_PROVIDER_IDS.map((id) => {
    const found = providerById.get(id);
    if (found) return found;
    return {
      provider: id,
      label: COMPARISON_PROVIDER_LABELS[id] ?? id,
      total_cost: 0,
      input_per_million_usd: 0,
      output_per_million_usd: 0,
      price_source: '',
      price_as_of: '',
    };
  });

  return (
    <div className="hud-panel p-6">
      <h3 className="hud-label flex items-center gap-2 mb-4">
        <DollarSign size={12} style={{ color: 'var(--color-success)' }} />
        Cost Comparison*
      </h3>

      {/* Local stats */}
      <div
        className="flex items-center gap-3 p-3 rounded-lg mb-3"
        style={{ background: 'var(--color-accent-subtle)', border: '1px solid var(--color-accent)' }}
      >
        <HardDrive size={18} style={{ color: 'var(--color-accent)' }} />
        <div className="flex-1">
          <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>
            Local (your hardware)
          </div>
          <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {savings.total_calls} requests &middot; {savings.total_tokens.toLocaleString()} tokens
          </div>
        </div>
        <div className="text-right">
          <div className="text-lg font-semibold" style={{ color: 'var(--color-success)' }}>
            ${savings.local_cost.toFixed(4)}
          </div>
          <div className="text-[10px]" style={{ color: 'var(--color-text-tertiary)' }}>
            electricity only
          </div>
        </div>
      </div>

      {/* Cloud comparisons */}
      <div className="flex flex-col gap-2">
        {comparisonProviders.map((provider) => {
          const cost = provider.total_cost;
          const saved = cost - savings.local_cost;
          return (
            <div
              key={provider.provider}
              className="flex items-center gap-3 p-3 rounded-lg"
              style={{ background: 'var(--color-bg-secondary)' }}
            >
              <Cloud size={16} style={{ color: 'var(--color-text-tertiary)' }} />
              <div className="flex-1">
                <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                  {provider.label}
                </div>
              </div>
              <div className="text-right">
                <div className="text-sm font-mono" style={{ color: 'var(--color-text)' }}>
                  ${cost.toFixed(4)}
                </div>
                {saved > 0 && (
                  <div className="text-[10px] flex items-center gap-0.5 justify-end" style={{ color: 'var(--color-success)' }}>
                    <TrendingDown size={10} />
                    ${saved.toFixed(4)} saved
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      <div className="mt-3 pt-3" style={{ borderTop: '1px solid var(--color-border)' }}>
        <p className="text-[10px] leading-relaxed" style={{ color: 'var(--color-text-tertiary)' }}>
          *Savings estimates assume local models (e.g. Qwen, Nemotron, Kimi) produce roughly the same number of tokens per request, on average, as closed-source cloud models.
        </p>
      </div>
    </div>
  );
}
