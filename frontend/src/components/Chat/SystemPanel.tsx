import { useState, useEffect } from 'react';
import {
  Zap,
  Activity,
  Thermometer,
  DollarSign,
  TrendingDown,
  Cloud,
  HardDrive,
  Hash,
  X,
} from 'lucide-react';
import { useAppStore } from '../../lib/store';
import { getBase } from '../../lib/api';

interface EnergyData {
  total_energy_j?: number;
  energy_per_token_j?: number;
  avg_power_w?: number;
  cpu_temp_c?: number | null;
  gpu_temp_c?: number | null;
}

interface TelemetryStats {
  total_requests?: number;
  total_tokens?: number;
}

const PRIMARY_PROVIDER_IDS = ['claude-sonnet-5-5', 'gemini-4-neon', 'gpt-4o'];

const PRIMARY_PROVIDER_LABELS: Record<string, string> = {
  'claude-sonnet-5-5': 'Claude Sonnet 5.5',
  'gemini-4-neon': 'Gemini 4 Neon',
  'gpt-4o': 'GPT-4o',
};

export function SystemPanel() {
  const savings = useAppStore((s) => s.savings);
  const toggleSystemPanel = useAppStore((s) => s.toggleSystemPanel);
  const liveEnergy = useAppStore((s) => s.liveEnergy);
  const [energy, setEnergy] = useState<EnergyData | null>(null);
  const [telemetry, setTelemetry] = useState<TelemetryStats | null>(null);
  const cloudModels = PRIMARY_PROVIDER_IDS.map((id) => {
    const found = savings?.per_provider?.find((p) => p.provider === id);
    if (found) return found;
    return {
      provider: id,
      label: PRIMARY_PROVIDER_LABELS[id] ?? id,
      total_cost: 0,
      input_per_million_usd: 0,
      output_per_million_usd: 0,
      price_source: '',
      price_as_of: '',
    };
  });
  const realProviderCosts = savings?.real_cost_by_provider ?? [];

  // Simple polling without callbacks to avoid infinite loops
  useEffect(() => {
    const fetchData = async () => {
      try {
        const base = getBase();
        const [energyRes, telRes] = await Promise.allSettled([
          fetch(`${base}/v1/telemetry/energy`).then((r) => (r.ok ? r.json() : null)),
          fetch(`${base}/v1/telemetry/stats`).then((r) => (r.ok ? r.json() : null)),
        ]);
        if (energyRes.status === 'fulfilled' && energyRes.value) {
          setEnergy(energyRes.value as EnergyData);
        }
        if (telRes.status === 'fulfilled' && telRes.value) {
          setTelemetry(telRes.value as TelemetryStats);
        }
      } catch {
        // best-effort
      }
    };

    fetchData();
    const interval = setInterval(fetchData, 2000);
    return () => clearInterval(interval);
  }, []);

  const promptK = (savings?.total_prompt_tokens ?? 0) / 1000;
  const completionK = (savings?.total_completion_tokens ?? 0) / 1000;

  return (
    <div
      className="flex flex-col h-full overflow-y-auto"
      style={{
        width: 280,
        minWidth: 280,
        background: 'var(--color-bg)',
        borderLeft: '1px solid var(--color-border)',
      }}
    >
      {/* Header */}
      <div
        className="flex items-center justify-between px-4 py-3 shrink-0"
        style={{ borderBottom: '1px solid var(--color-border)' }}
      >
        <span className="text-xs font-semibold tracking-wide uppercase" style={{ color: 'var(--color-text-secondary)' }}>
          System
        </span>
        <button
          onClick={toggleSystemPanel}
          className="p-1 rounded-md transition-colors cursor-pointer"
          style={{ color: 'var(--color-text-tertiary)' }}
          title="Close panel"
        >
          <X size={14} />
        </button>
      </div>

      <div className="flex flex-col gap-4 p-4">
        {/* Session Stats */}
        <section>
          <h4 className="text-[11px] font-medium uppercase tracking-wide mb-2" style={{ color: 'var(--color-text-tertiary)' }}>
            Session
          </h4>
          <div className="grid grid-cols-2 gap-2">
            <MiniStat icon={Hash} label="Requests" value={String(telemetry?.total_requests ?? savings?.total_calls ?? 0)} />
            <MiniStat icon={Hash} label="Output Tokens" value={formatNumber(telemetry?.total_tokens ?? savings?.total_completion_tokens ?? 0)} />
          </div>
        </section>

        {/* Device */}
        <section>
          <h4 className="text-[11px] font-medium uppercase tracking-wide mb-2" style={{ color: 'var(--color-text-tertiary)' }}>
            Device
          </h4>
          <div className="grid grid-cols-2 gap-2">
            {energy?.cpu_temp_c != null && (
              <MiniStat icon={Thermometer} label="CPU Temp" value={String(Math.round(energy.cpu_temp_c))} unit="°C" />
            )}
            {energy?.gpu_temp_c != null && (
              <MiniStat icon={Thermometer} label="GPU Temp" value={String(Math.round(energy.gpu_temp_c))} unit="°C" />
            )}
            <MiniStat
              icon={Zap}
              label="Power"
              value={(liveEnergy?.power_w ?? energy?.avg_power_w ?? 0).toFixed(1)}
              unit="W"
            />
            <MiniStat
              icon={Activity}
              label="Energy"
              value={(
                ((liveEnergy?.energy_j ?? energy?.total_energy_j ?? 0) / 1000)
              ).toFixed(1)}
              unit="kJ"
            />
          </div>
        </section>


        {/* Cost Comparison */}
        <section>
          <h4 className="text-[11px] font-medium uppercase tracking-wide mb-2" style={{ color: 'var(--color-text-tertiary)' }}>
            Cost Comparison
          </h4>

          {/* Local */}
          <div
            className="flex items-center gap-2 rounded-lg px-3 py-2 mb-2"
            style={{ background: 'var(--color-accent-subtle)', border: '1px solid var(--color-accent)' }}
          >
            <HardDrive size={14} style={{ color: 'var(--color-accent)' }} />
            <div className="flex-1 min-w-0">
              <div className="text-xs font-medium truncate" style={{ color: 'var(--color-text)' }}>Local</div>
            </div>
            <div className="text-sm font-semibold" style={{ color: 'var(--color-success)' }}>
              ${(savings?.local_cost ?? 0).toFixed(4)}
            </div>
          </div>

          {/* Cloud providers */}
          <div className="flex flex-col gap-1.5">
            {cloudModels.map((provider) => {
              const cost = provider.total_cost;
              const saved = cost - (savings?.local_cost ?? 0);
              return (
                <div
                  key={provider.provider}
                  className="flex items-center gap-2 rounded-lg px-3 py-2"
                  style={{
                    background: 'var(--color-bg-secondary)',
                    border: provider.provider === PRIMARY_PROVIDER_IDS[0]
                      ? '1px solid var(--color-border-accent, var(--color-accent))'
                      : '1px solid transparent',
                  }}
                >
                  <Cloud size={14} style={{ color: 'var(--color-text-tertiary)' }} />
                  <div className="flex-1 min-w-0">
                    <div
                      className="text-xs truncate"
                      style={{
                        color: provider.provider === PRIMARY_PROVIDER_IDS[0] ? 'var(--color-text)' : 'var(--color-text-secondary)',
                        fontWeight: provider.provider === PRIMARY_PROVIDER_IDS[0] ? 500 : 400,
                      }}
                    >
                      {provider.label}
                    </div>
                  </div>
                  <div className="text-right shrink-0">
                    <div className="text-xs font-mono" style={{ color: 'var(--color-text)' }}>
                      ${cost.toFixed(4)}
                    </div>
                    {saved > 0.0001 && (
                      <div className="text-[9px] flex items-center gap-0.5 justify-end" style={{ color: 'var(--color-success)' }}>
                        <TrendingDown size={8} />
                        ${saved.toFixed(4)}
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>


        </section>

        <section>
          <h4 className="text-[11px] font-medium uppercase tracking-wide mb-2" style={{ color: 'var(--color-text-tertiary)' }}>
            Cloud Model Costs (real spend)
          </h4>
          {realProviderCosts.length === 0 ? (
            <div className="text-[10px] px-1" style={{ color: 'var(--color-text-tertiary)' }}>
              No cloud provider requests yet.
            </div>
          ) : (
            <div className="grid gap-2">
              {realProviderCosts.map((provider) => (
                <div
                  key={provider.provider}
                  className="flex items-center justify-between rounded-lg px-3 py-2 text-[10px]"
                  style={{
                    background: 'var(--color-bg-secondary)',
                    border: '1px solid var(--color-border)',
                  }}
                >
                  <div className="font-medium" style={{ color: 'var(--color-text)' }}>
                    {provider.label}
                  </div>
                  <div className="font-semibold" style={{ color: 'var(--color-accent)' }}>
                    ${provider.total_cost.toFixed(4)}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

      </div>
    </div>
  );
}

function MiniStat({
  icon: Icon,
  label,
  value,
  unit,
}: {
  icon: typeof Zap;
  label: string;
  value: string;
  unit?: string;
}) {
  return (
    <div
      className="rounded-lg px-2.5 py-2"
      style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}
    >
      <div className="flex items-center gap-1 mb-0.5">
        <Icon size={10} style={{ color: 'var(--color-accent)' }} />
        <span className="text-[10px]" style={{ color: 'var(--color-text-tertiary)' }}>
          {label}
        </span>
      </div>
      <div className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>
        {value}
        {unit && (
          <span className="text-[10px] font-normal ml-0.5" style={{ color: 'var(--color-text-tertiary)' }}>
            {unit}
          </span>
        )}
      </div>
    </div>
  );
}

function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}
