<script lang="ts">
  import { Plus, Play, PlayCircle, Trash2, Clapperboard } from "@lucide/svelte";
  import VideoDialog from "../media/VideoDialog.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { untrack } from "svelte";
  import { call } from "../api";
  import { act, app, run } from "../state.svelte";
  import { fmt, num, pretty, toShown, unitOf, type Dim } from "../units.svelte";
  import Plot, { type Series } from "../Plot.svelte";
  import SimForm from "./SimForm.svelte";
  import Panel from "../Panel.svelte";

  // OpenRocket's "Flight simulations" tab: the simulations with their results, the launch conditions of the
  // selected one, and its flight plotted.

  interface Sim {
    index: number;
    name: string;
    configuration: string;
    configurationName: string;
    status: string;
    options: Record<string, any>;
    results: Record<string, any>;
  }

  let sims = $state<Sim[]>([]);
  let configs = $state<{ id: string; name: string }[]>([]);
  let sel = $state(0);
  let video = $state(false);
  let running = $state(false);

  async function load() {
    if (!app.design) return;
    const r = await run(() => call<{ simulations: Sim[]; configurations: { id: string; name: string }[] }>("app_sims", { designId: app.design!.designId }));
    if (r) {
      sims = r.simulations;
      configs = r.configurations;
      if (sel >= sims.length) sel = Math.max(0, sims.length - 1);
    }
  }

  $effect(() => {
    void app.revision;
    untrack(load); // load() touches other state (the busy counter): only a new revision reloads
  });

  let current = $derived(sims[sel]);

  async function runSims(all: boolean) {
    running = true;
    const r = await act("app_sim_run", all ? {} : { simulation: sel });
    running = false;
    for (const x of r?.runs ?? []) if (x.error) app.toasts.push({ id: Date.now() + Math.random(), text: `${x.name}: ${x.error}`, kind: "error" });
    plotKey++;
  }

  async function newSim() {
    const r = await act("app_sim_new", {});
    if (r) sel = r.index;
  }

  const STATUS: Record<string, [string, string]> = {
    UPTODATE: ["good", "Up to date"],
    LOADED: ["good", "Results from the file"],
    OUTDATED: ["warn", "Out of date: the design changed since this ran"],
    NOT_SIMULATED: ["none", "Not run yet"],
    CANT_RUN: ["bad", "Cannot run"],
    EXTERNAL: ["none", "External"],
  };

  // ------------------------------------------------------------------ plot
  interface Var {
    key: string;
    name: string;
    dim?: string;
    values: (number | null)[];
  }
  let series = $state<{ branches: string[]; branch: number; variables: Var[]; events: { type: string; time: number }[] } | null>(null);
  let branch = $state(0);
  let chosen = $state<string[]>(["Altitude", "Vertical velocity", "Vertical acceleration"]);
  let plotKey = $state(0);

  $effect(() => {
    void plotKey;
    void app.revision;
    const s = current;
    const b = branch;
    series = null;
    if (!s || !app.design || !(s.status === "UPTODATE" || s.status === "LOADED" || s.status === "OUTDATED") || !s.results?.apogee) return;
    call("app_series", { designId: app.design.designId, simulation: s.index, branch: b })
      .then((r) => (series = r))
      .catch(() => (series = null));
  });

  const COLORS = ["#e8590c", "#1d6fe0", "#1f9d55", "#9b51e0", "#d63384", "#0aa2c0", "#8a6420"];
  // Launch, first ignition and liftoff all sit at t = 0: rail exit stands for them.
  const EVENT: Record<string, string> = { IGNITION: "Ignition", LAUNCHROD: "Rail exit",
    BURNOUT: "Burnout", EJECTION_CHARGE: "Ejection", STAGE_SEPARATION: "Separation", APOGEE: "Apogee",
    RECOVERY_DEVICE_DEPLOYMENT: "Deploy", GROUND_HIT: "Landing" };

  let plot = $derived.by(() => {
    if (!series) return null;
    const time = series.variables.find((v) => v.name === "Time");
    if (!time) return null;
    const picked = series.variables.filter((v) => chosen.includes(v.name));
    const dims = [...new Set(picked.map((v) => v.dim ?? ""))];
    const out: Series[] = picked.map((v, i) => ({
      label: v.name + (v.dim ? ` (${pretty(unitOf(v.dim as Dim).unit)})` : ""),
      values: v.values.map((x) => (x === null ? null : toShown(x, v.dim as Dim))),
      color: COLORS[i % COLORS.length],
      axis: dims.indexOf(v.dim ?? "") === 0 ? "left" : "right",
    }));
    const label = (d: string) => (d ? picked.filter((v) => (v.dim ?? "") === d).map((v) => v.name).join(", ") : "");
    return {
      x: time.values as number[],
      series: out,
      left: label(dims[0] ?? ""),
      right: dims.slice(1).map(label).join(" / "),
      events: series.events.filter((e) => EVENT[e.type] && !(e.type === "IGNITION" && e.time < 0.5)).map((e) => ({ x: e.time, label: EVENT[e.type] })),
    };
  });

  const PRESETS: [string, string[]][] = [
    ["Vertical motion", ["Altitude", "Vertical velocity", "Vertical acceleration"]],
    ["Total motion", ["Altitude", "Total velocity", "Total acceleration"]],
    ["Stability", ["Stability margin calibers", "CP location", "CG location"]],
    ["Drag", ["Drag coefficient", "Mach number"]],
    ["Forces", ["Thrust", "Drag force", "Mass"]],
    ["Angle of attack", ["Angle of attack", "Total velocity"]],
  ];

  let preset = $derived(PRESETS.find(([, vars]) => vars.length === chosen.length && vars.every((v) => chosen.includes(v)))?.[0] ?? "");

  function toggleVar(name: string) {
    chosen = chosen.includes(name) ? chosen.filter((n) => n !== name) : [...chosen, name];
  }

  let names = $derived((series?.variables ?? []).map((v) => v.name).filter((n) => n !== "Time"));
</script>

<div class="page">
  <div class="toolbar">
    <button class="btn" onclick={newSim}><Plus size={15} /> New</button>
    <button class="btn primary" disabled={!current || running} onclick={() => runSims(false)}><Play size={15} /> Run</button>
    <button class="btn" disabled={!sims.length || running} onclick={() => runSims(true)}><PlayCircle size={15} /> Run all</button>
    <button class="btn" disabled={!current} onclick={() => current && act("app_sim_delete", { simulation: current.index })}><Trash2 size={15} /> Delete</button>
    <button class="btn" disabled={!current || running} onclick={() => (video = true)} title="The selected flight as a video"><Clapperboard size={15} /> Video</button>
    {#if running}<RocketLoader size="small" />{/if}
  </div>

  <Panel id="flights.list" title="Simulations">
    <div class="list">
      <table class="grid">
        <thead>
          <tr>
            <th></th><th>Name</th><th>Configuration</th><th>Apogee</th><th>Max speed</th><th>Max accel.</th><th>Time to apogee</th>
            <th>Flight time</th><th>Ground hit</th><th>Rail exit</th><th>Optimum delay</th>
          </tr>
        </thead>
        <tbody>
          {#each sims as s, i (s.index)}
            {@const st = STATUS[s.status] ?? ["none", s.status]}
            <tr class:sel={i === sel} onclick={() => (sel = i)}>
              <td><span class="dot {st[0]}" title={st[1]}></span></td>
              <td>{s.name}</td>
              <td>{s.configurationName}</td>
              <td class="num">{fmt(s.results.apogee, "DISTANCE")}</td>
              <td class="num">{fmt(s.results.maxVelocity, "VELOCITY")}{#if s.results.maxMach}<span class="muted mach">Mach {s.results.maxMach.toFixed(2)}</span>{/if}</td>
              <td class="num">{fmt(s.results.maxAcceleration, "ACCELERATION")}</td>
              <td class="num">{fmt(s.results.timeToApogee, "TIME", 3)}</td>
              <td class="num">{fmt(s.results.flightTime, "TIME", 3)}</td>
              <td class="num">{fmt(s.results.groundHitVelocity, "VELOCITY", 3)}</td>
              <td class="num">{fmt(s.results.railExitVelocity, "VELOCITY", 3)}</td>
              <td class="num">{fmt(s.results.optimumDelay, "TIME", 2)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
      {#if !sims.length}<p class="muted empty">No simulations.</p>{/if}
    </div>
  </Panel>

  {#if current}
    <div class="bottom">
      <div class="formcol">
        <Panel id="flights.conditions" title="Launch conditions: {current.name}">
          <SimForm sim={current} {configs} />
        </Panel>
      </div>
      <Panel id="flights.plot" title="Plot">
        {#snippet tools()}
          <select class="select small" value={preset} onchange={(e) => {
            const p = PRESETS.find(([l]) => l === (e.target as HTMLSelectElement).value);
            if (p) chosen = p[1];
          }}>
            {#if !preset}<option value="">Custom</option>{/if}
            {#each PRESETS as [label] (label)}<option value={label}>{label}</option>{/each}
          </select>
          {#if (series?.branches.length ?? 0) > 1}
            <select class="select small" bind:value={branch}>
              {#each series!.branches as b, i}<option value={i}>{b}</option>{/each}
            </select>
          {/if}
        {/snippet}
        <div class="plotbody">
          {#if plot}
            <Plot x={plot?.x ?? []} series={plot?.series ?? []} xLabel="Time (s)" leftLabel={plot?.left ?? ""} rightLabel={plot?.right ?? ""} events={plot?.events ?? []} height={300} />
            <details>
              <summary>Variables</summary>
              <div class="vars">
                {#each names as n (n)}
                  <label class="var"><input type="checkbox" checked={chosen.includes(n)} onchange={() => toggleVar(n)} /> {n}</label>
                {/each}
              </div>
            </details>
          {:else}
            <p class="muted empty">{current.results?.apogee ? "Loading…" : "Not run yet."}</p>
          {/if}
        </div>
      </Panel>
    </div>
  {/if}
</div>

{#if video && current}<VideoDialog simulation={current.index} name={current.name} close={() => (video = false)} />{/if}

<style>
  .page {
    flex: 1;
    min-height: 0;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 6px;
  }
  .toolbar {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .list {
    max-height: 32vh;
    overflow: auto;
  }
  .list tr {
    cursor: default;
  }
  .mach {
    margin-left: 6px;
  }
  .dot {
    display: inline-block;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--line-strong);
  }
  .dot.good {
    background: var(--good);
  }
  .dot.warn {
    background: var(--warn);
  }
  .dot.bad {
    background: var(--bad);
  }
  .empty {
    padding: 8px 10px;
    margin: 0;
  }
  .bottom {
    display: flex;
    gap: 6px;
    align-items: flex-start;
    min-height: 0;
  }
  .formcol {
    width: 360px;
    flex: none;
  }
  .bottom > :global(.panel:last-child) {
    flex: 1;
  }
  .plotbody {
    padding: 6px 10px;
    min-width: 0;
  }
  .select.small {
    height: 24px;
    font-size: 12px;
  }
  details {
    margin-top: 4px;
  }
  summary {
    cursor: pointer;
    color: var(--ink-2);
  }
  .vars {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
    gap: 0 12px;
    padding: 6px 0;
  }
  .var {
    display: flex;
    gap: 6px;
    align-items: center;
  }
</style>
