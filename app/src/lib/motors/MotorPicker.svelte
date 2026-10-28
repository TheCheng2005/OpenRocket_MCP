<script lang="ts">
  import { X, Search } from "@lucide/svelte";
  import { call } from "../api";
  import { act, app, run } from "../state.svelte";
  import { fmt, num, pretty, toShown, unitOf } from "../units.svelte";
  import Plot from "../Plot.svelte";

  // OpenRocket's motor selection dialog: search the database, see the thrust curve, pick the delay.
  let { mount, configuration, close }: { mount: { id: string; name: string; innerDiameter: number }; configuration: string; close: () => void } =
    $props();

  interface MotorRow {
    digest: string;
    designation: string;
    commonName: string;
    manufacturer: string;
    class: string;
    diameter: number;
    length: number;
    totalImpulse: number;
    averageThrust: number;
    maxThrust: number;
    burnTime: number;
    launchMass: number;
    propellantMass: number;
    type: string;
    delays: number[];
    available: boolean;
  }

  const CLASSES = "ABCDEFGHIJKLMNOPQ".split("");
  let text = $state("");
  let minClass = $state("");
  let maxClass = $state("");
  let fits = $state(true);
  let availableOnly = $state(false);
  let rows = $state<MotorRow[]>([]);
  let matches = $state(0);
  let chosen = $state<MotorRow | null>(null);
  let curve = $state<{ time: number[]; thrust: number[] } | null>(null);
  let delay = $state("auto");

  let timer: ReturnType<typeof setTimeout>;
  $effect(() => {
    const args: Record<string, unknown> = { availableOnly, limit: 400 };
    if (text.trim()) args.text = text.trim();
    if (minClass) args.minClass = minClass;
    if (maxClass) args.maxClass = maxClass;
    if (fits && app.design) Object.assign(args, { designId: app.design.designId, mount: mount.id });
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const r = await run(() => call<{ matches: number; motors: MotorRow[] }>("app_motors", args));
      if (r) {
        rows = r.motors;
        matches = r.matches;
      }
    }, 200);
  });

  async function pick(m: MotorRow) {
    chosen = m;
    delay = "auto";
    curve = (await run(() => call<{ time: number[]; thrust: number[] }>("app_motor_curve", { digest: m.digest }))) ?? null;
  }

  async function use() {
    if (!chosen) return;
    const args: Record<string, unknown> = { mount: mount.id, motor: chosen.digest, configuration };
    if (delay !== "auto" && delay !== "plugged") args.ejectionDelay = Number(delay);
    const r = await act("set_motor", args);
    if (r && delay === "plugged") await act("app_motor_delay", { mount: mount.id, configuration, plugged: true });
    if (r) close();
  }

  let delays = $derived(chosen ? chosen.delays.filter((d) => d !== null && Number.isFinite(d)) : []);
</script>

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="dlg card" role="dialog" aria-label="Choose a motor">
    <header>
      <div>
        <h3>Choose a motor for {mount.name}</h3>
        <div class="muted">Mount inside diameter {fmt(mount.innerDiameter, "LENGTH")}</div>
      </div>
      <button class="btn ghost" onclick={close} aria-label="Close"><X size={18} /></button>
    </header>
    <div class="filters">
      <label class="search">
        <Search size={16} />
        <input class="input" placeholder="Search: manufacturer or designation (e.g. CTI L1115)" bind:value={text} />
      </label>
      <label>Class
        <select class="select" bind:value={minClass}><option value="">any</option>{#each CLASSES as c}<option>{c}</option>{/each}</select>
        to
        <select class="select" bind:value={maxClass}><option value="">any</option>{#each CLASSES as c}<option>{c}</option>{/each}</select>
      </label>
      <label class="check"><input type="checkbox" bind:checked={fits} /> Fits this mount</label>
      <label class="check"><input type="checkbox" bind:checked={availableOnly} /> In production only</label>
      <span class="muted count">{matches} motors</span>
    </div>
    <div class="main">
      <div class="list">
        <table class="grid">
          <thead>
            <tr><th>Manufacturer</th><th>Motor</th><th>Class</th><th>Ø</th><th>Length</th><th>Impulse</th><th>Avg thrust</th><th>Burn</th></tr>
          </thead>
          <tbody>
            {#each rows as m (m.digest)}
              <tr class:sel={chosen?.digest === m.digest} onclick={() => pick(m)} ondblclick={() => (pick(m), use())}>
                <td>{m.manufacturer}</td>
                <td><b>{m.designation}</b>{#if !m.available} <span class="muted">(out of production)</span>{/if}</td>
                <td>{m.class}</td>
                <td class="num">{fmt(m.diameter, "LENGTH", 3)}</td>
                <td class="num">{fmt(m.length, "LENGTH", 3)}</td>
                <td class="num">{fmt(m.totalImpulse, "IMPULSE")}</td>
                <td class="num">{fmt(m.averageThrust, "FORCE")}</td>
                <td class="num">{fmt(m.burnTime, "TIME", 3)}</td>
              </tr>
            {/each}
          </tbody>
        </table>
        {#if rows.length === 0}<p class="muted empty">No motors match. Untick “Fits this mount” or widen the class range.</p>{/if}
      </div>
      <aside class="detail">
        {#if chosen}
          <h4>{chosen.manufacturer} {chosen.designation}</h4>
          {#if curve}
            <Plot x={curve.time} series={[{ label: "Thrust", values: curve.thrust.map((t) => toShown(t, "FORCE")), color: "#e8590c" }]}
              xLabel="Time (s)" leftLabel={"Thrust (" + pretty(unitOf("FORCE").unit) + ")"} height={200} />
          {/if}
          <dl>
            <dt>Total impulse</dt><dd>{fmt(chosen.totalImpulse, "IMPULSE")}</dd>
            <dt>Average / max thrust</dt><dd>{fmt(chosen.averageThrust, "FORCE")} / {fmt(chosen.maxThrust, "FORCE")}</dd>
            <dt>Burn time</dt><dd>{fmt(chosen.burnTime, "TIME", 3)}</dd>
            <dt>Mass / propellant</dt><dd>{fmt(chosen.launchMass, "MASS")} / {fmt(chosen.propellantMass, "MASS")}</dd>
            <dt>Type</dt><dd>{chosen.type.toLowerCase()}</dd>
          </dl>
          <label class="delay">Ejection delay
            <select class="select" bind:value={delay}>
              <option value="auto">Motor's default</option>
              {#each delays as d}<option value={String(d)}>{num(d, 3)} s</option>{/each}
              <option value="plugged">Plugged (no ejection charge)</option>
            </select>
          </label>
          <button class="btn primary big" onclick={use}>Use this motor</button>
        {:else}
          <p class="muted">Select a motor to see its thrust curve.</p>
        {/if}
      </aside>
    </div>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 35%);
    display: grid;
    place-items: center;
    z-index: 30;
  }
  .dlg {
    width: min(1180px, calc(100vw - 40px));
    height: min(760px, calc(100vh - 40px));
    display: flex;
    flex-direction: column;
    box-shadow: var(--shadow-pop);
    overflow: hidden;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    padding: 16px 18px 10px;
  }
  h3 {
    margin: 0;
  }
  .filters {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px 16px;
    padding: 0 18px 12px;
    border-bottom: 1px solid var(--line);
    font-size: 13px;
    color: var(--ink-2);
  }
  .filters label {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .search {
    flex: 1;
    min-width: 280px;
  }
  .search .input {
    flex: 1;
  }
  .check input {
    width: 16px;
    height: 16px;
  }
  .count {
    margin-left: auto;
  }
  .main {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr 360px;
  }
  .list {
    overflow: auto;
    min-height: 0;
  }
  .list tr {
    cursor: pointer;
  }
  .empty {
    padding: 20px;
  }
  .detail {
    border-left: 1px solid var(--line);
    padding: 14px 16px;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  h4 {
    margin: 0;
    font-size: 16px;
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 4px 12px;
    margin: 0;
    font-size: 13px;
  }
  dt {
    color: var(--ink-3);
  }
  dd {
    margin: 0;
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  .delay {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 13px;
    color: var(--ink-2);
  }
</style>
