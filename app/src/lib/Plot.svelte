<script module lang="ts">
  export interface Series {
    label: string;
    values: (number | null)[];
    color: string;
    axis?: "left" | "right";
  }
</script>

<script lang="ts">
  import { onMount } from "svelte";
  import uPlot from "uplot";
  import "uplot/dist/uPlot.min.css";

  // A line plot: one x column, any number of y series on up to two axes (left and right), event lines.

  let {
    x,
    series,
    xLabel = "",
    leftLabel = "",
    rightLabel = "",
    events = [],
    height = 280,
  }: {
    x: number[];
    series: Series[];
    xLabel?: string;
    leftLabel?: string;
    rightLabel?: string;
    events?: { x: number; label: string }[];
    height?: number;
  } = $props();

  let host: HTMLDivElement;
  let plot: uPlot | null = null;

  const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();

  function make() {
    plot?.destroy();
    plot = null;
    if (!host || x.length === 0) return;
    const ink = css("--ink-2");
    const grid = css("--grid");
    const hasRight = series.some((s) => s.axis === "right");
    const axisStyle = { stroke: ink, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid, width: 1 } };
    const opts: uPlot.Options = {
      width: host.clientWidth || 600,
      height,
      scales: { x: { time: false } },
      legend: { live: true },
      cursor: { drag: { x: true, y: false } },
      series: [
        { label: xLabel },
        ...series.map((s) => ({ label: s.label, stroke: s.color, width: 2, scale: s.axis === "right" ? "right" : "left", spanGaps: false })),
      ],
      axes: [
        { ...axisStyle, label: xLabel },
        { ...axisStyle, scale: "left", label: leftLabel },
        ...(hasRight ? [{ ...axisStyle, scale: "right", side: 1 as const, label: rightLabel, grid: { show: false } }] : []),
      ],
      plugins: [
        {
          hooks: {
            draw: [
              (u: uPlot) => {
                const ctx = u.ctx;
                ctx.save();
                ctx.font = `${11 * devicePixelRatio}px sans-serif`;
                ctx.fillStyle = css("--ink-3");
                ctx.strokeStyle = css("--ink-3");
                ctx.setLineDash([4 * devicePixelRatio, 4 * devicePixelRatio]);
                for (const ev of events) {
                  const px = u.valToPos(ev.x, "x", true);
                  if (px < u.bbox.left || px > u.bbox.left + u.bbox.width) continue;
                  ctx.beginPath();
                  ctx.moveTo(px, u.bbox.top);
                  ctx.lineTo(px, u.bbox.top + u.bbox.height);
                  ctx.stroke();
                  // Labels run up the line, so events close together stay readable.
                  ctx.save();
                  ctx.translate(px - 3 * devicePixelRatio, u.bbox.top + 4 * devicePixelRatio);
                  ctx.rotate(-Math.PI / 2);
                  ctx.textAlign = "right";
                  ctx.fillText(ev.label, 0, 0);
                  ctx.restore();
                }
                ctx.restore();
              },
            ],
          },
        },
      ],
    };
    plot = new uPlot(opts, [x, ...series.map((s) => s.values)] as uPlot.AlignedData, host);
  }

  onMount(() => {
    const ro = new ResizeObserver(() => plot?.setSize({ width: host.clientWidth, height }));
    ro.observe(host);
    return () => {
      ro.disconnect();
      plot?.destroy();
    };
  });

  $effect(() => {
    void x;
    void series;
    void events;
    make();
  });
</script>

<div class="plot" bind:this={host}></div>

<style>
  .plot {
    width: 100%;
    min-width: 0;
  }
  .plot :global(.u-legend) {
    font-size: 12px;
    color: var(--ink-2);
  }
  .plot :global(.u-select) {
    background: color-mix(in srgb, var(--select) 15%, transparent);
  }
</style>
