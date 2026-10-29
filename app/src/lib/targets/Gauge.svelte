<script lang="ts">
  // Where the flight's number sits against a target: the allowed band, the goal tick and the value's marker.
  let {
    value,
    min,
    max,
    goal,
    level = "",
  }: { value: number | null; min?: number; max?: number; goal?: number; level?: string } = $props();

  const W = 120;
  const H = 16;

  let g = $derived.by(() => {
    const pts = [value, min, max, goal].filter((v): v is number => v !== null && v !== undefined && Number.isFinite(v));
    if (pts.length < 2 || (min === undefined && max === undefined && goal === undefined)) return null;
    let lo = Math.min(...pts);
    let hi = Math.max(...pts);
    const span = hi - lo || Math.abs(hi) || 1;
    lo -= span * 0.15;
    hi += span * 0.15;
    const x = (v: number) => 2 + ((v - lo) / (hi - lo)) * (W - 4);
    return {
      band: [min === undefined ? 0 : x(min), max === undefined ? W : x(max)],
      goal: goal === undefined ? null : x(goal),
      value: value === null ? null : x(value),
    };
  });
</script>

{#if g}
  <svg width={W} height={H} viewBox="0 0 {W} {H}" aria-hidden="true">
    <rect x="0" y="6" width={W} height="4" rx="2" class="track" />
    <rect x={g.band[0]} y="5" width={Math.max(1, g.band[1] - g.band[0])} height="6" rx="2" class="band" />
    {#if g.goal !== null}<line x1={g.goal} x2={g.goal} y1="2" y2="14" class="goal" />{/if}
    {#if g.value !== null}<circle cx={g.value} cy="8" r="4.5" class="dot {level}" />{/if}
  </svg>
{/if}

<style>
  svg {
    display: block;
  }
  .track {
    fill: var(--surface-3);
  }
  .band {
    fill: var(--good-soft);
    stroke: var(--good);
    stroke-width: 0.8;
  }
  .goal {
    stroke: var(--ink-2);
    stroke-width: 1.5;
  }
  .dot {
    fill: var(--ink-2);
    stroke: var(--surface);
    stroke-width: 1.5;
  }
  .dot.pass {
    fill: var(--good);
  }
  .dot.warn {
    fill: var(--warn);
  }
  .dot.fail {
    fill: var(--bad);
  }
</style>
