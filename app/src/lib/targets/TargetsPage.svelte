<script lang="ts">
  import { untrack } from "svelte";
  import { RefreshCw } from "@lucide/svelte";
  import { app, checkTargets, setTargets, type Target } from "../state.svelte";
  import { fmt, num, toShown, unitOf, pretty, type Dim } from "../units.svelte";
  import Panel from "../Panel.svelte";
  import RuleCheck from "../design/RuleCheck.svelte";
  import RuleCheckStatus from "../design/RuleCheckStatus.svelte";
  import Gauge from "./Gauge.svelte";

  // The team's own targets with the margin to each, next to the competition rules. Both are checked again after
  // every edit; a limit typed here is saved in the team standards file, where Claude reads the same targets.
  let onlySet = $state(false);

  let t = $derived(app.targets && app.targets.designId === app.design?.designId ? app.targets : null);
  let rows = $derived((t?.targets ?? []).filter((r) => !onlySet || r.status));
  let counts = $derived.by(() => {
    const n = { FAIL: 0, WARN: 0, PASS: 0 };
    for (const r of t?.targets ?? []) if (r.status && r.status in n) n[r.status as keyof typeof n]++;
    return n;
  });

  $effect(() => {
    const id = app.design?.designId;
    if (id && untrack(() => app.targets?.designId !== id)) checkTargets();
  });

  const dimOf = (r: Target) => (r.dim ?? null) as Dim | null;
  const suffix = (r: Target) => (/Stability$/.test(r.id) ? "cal" : "");

  function shown(r: Target, si: number | null | undefined): string {
    if (si === null || si === undefined) return "–";
    const d = dimOf(r);
    return d ? fmt(si, d) : num(si) + (suffix(r) ? " " + suffix(r) : "");
  }

  function unit(r: Target): string {
    const d = dimOf(r);
    return d ? pretty(unitOf(d).unit) : suffix(r);
  }

  /** A limit as it sits in its box: a number in the chosen unit. */
  function boxed(r: Target, key: "min" | "max" | "goal"): string {
    if (r.from && key === "min") return "";
    const si = r[`${key}SI` as const];
    if (si === undefined || si === null) return "";
    const d = dimOf(r);
    return num(d ? toShown(si, d) : si);
  }

  function change(r: Target, key: "min" | "max" | "goal", text: string) {
    const v = text.trim();
    const d = dimOf(r);
    const value = v === "" ? null : /[a-z]/i.test(v) || !d ? v : `${v.replace(/,/g, "")} ${unitOf(d).unit}`;
    setTargets({ [r.id]: { [key]: value } });
  }

  /** Margin to the nearer limit, in the chosen unit, worded. */
  function margin(r: Target): string {
    const v = r.valueSI;
    if (v === null || (r.minSI === undefined && r.maxSI === undefined)) return "";
    const toMin = r.minSI === undefined ? Infinity : v - r.minSI;
    const toMax = r.maxSI === undefined ? Infinity : r.maxSI - v;
    const atMin = toMin <= toMax;
    const m = Math.min(toMin, toMax);
    const words = atMin ? (m >= 0 ? "above min" : "below min") : m >= 0 ? "under max" : "over max";
    return `${shown(r, Math.abs(m))} ${words}`;
  }

  function fromGoal(r: Target): string {
    const d = (r.valueSI ?? 0) - (r.goalSI ?? 0);
    return `${d >= 0 ? "+" : "−"}${shown(r, Math.abs(d))} from the goal`;
  }

  function level(r: Target): string {
    return (r.status ?? "").toLowerCase();
  }
</script>

<div class="page">
  <div class="main">
    <Panel id="targets.list" title="Targets" fill>
      {#snippet status()}
        <span class="counts">
          {#if app.targeting}<span>checking…</span>{/if}
          {#if t?.targets}
            <span class:bad={counts.FAIL > 0}>{counts.FAIL} missed</span>
            <span class:warn={counts.WARN > 0}>{counts.WARN} close</span>
            <span>{counts.PASS} met</span>
          {/if}
        </span>
        <button class="tool" title="Check again" onclick={() => checkTargets()}><RefreshCw size={14} /></button>
      {/snippet}
      {#snippet tools()}
        <label class="only"><input type="checkbox" bind:checked={onlySet} /> Only targets set</label>
      {/snippet}
      <div class="body" class:stale={app.targeting}>
        {#if !t}
          <p class="muted pad">Flying the design…</p>
        {:else if t.error}
          <p class="muted pad">{t.error}</p>
        {:else}
          {#if t.PROBLEMS?.length}
            <div class="problems">{#each t.PROBLEMS as p}<div>{p}</div>{/each}</div>
          {/if}
          <table class="grid">
            <thead>
              <tr>
                <th></th>
                <th>Target</th>
                <th class="n">Now</th>
                <th>Minimum</th>
                <th>Goal</th>
                <th>Maximum</th>
                <th></th>
                <th>Margin</th>
                <th class="g"></th>
              </tr>
            </thead>
            <tbody>
              {#each rows as r (r.id)}
                <tr class={level(r)} class:unset={!r.status}>
                  <td class="st {level(r)}">{r.status === "FAIL" ? "Missed" : r.status === "WARN" ? "Close" : r.status === "PASS" ? "Met" : ""}</td>
                  <td class="label">
                    {r.label}
                    {#if r.from}<div class="sub">team default</div>{/if}
                    {#if r.note}<div class="sub">{r.note}</div>{/if}
                  </td>
                  <td class="n now">{shown(r, r.valueSI)}</td>
                  {#each ["min", "goal", "max"] as const as key (key)}
                    <td class="lim">
                      <input
                        class="input"
                        value={boxed(r, key)}
                        placeholder={r.from && key === "min" ? boxed({ ...r, from: undefined }, "min") : ""}
                        aria-label="{r.label} {key}"
                        onchange={(e) => change(r, key, (e.target as HTMLInputElement).value)}
                      />
                    </td>
                  {/each}
                  <td class="u">{unit(r)}</td>
                  <td class="margin">{margin(r)}{#if r.goalSI !== undefined && r.valueSI !== null}<div class="sub">{fromGoal(r)}</div>{/if}</td>
                  <td class="g"><Gauge value={r.valueSI} min={r.minSI} max={r.maxSI} goal={r.goalSI} level={level(r)} /></td>
                </tr>
              {/each}
            </tbody>
          </table>
          <p class="muted foot">
            Flight: {t.simulation}. A target is close when it is met by less than 5%. Targets are kept in the team standards
            file (openrocket-mcp.json in the designs folder), so Claude checks the same ones.
          </p>
        {/if}
      </div>
    </Panel>
  </div>

  <div class="side">
    <Panel id="targets.rules" title="Competition rules" fill>
      {#snippet status()}<RuleCheckStatus />{/snippet}
      <RuleCheck tall />
    </Panel>
  </div>
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr);
    gap: 6px;
    padding: 6px;
  }
  @media (max-width: 1100px) {
    .page {
      grid-template-columns: 1fr;
      overflow: auto;
    }
  }
  .main,
  .side {
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .body {
    overflow: auto;
  }
  .body.stale {
    opacity: 0.75;
  }
  .pad {
    margin: 0;
    padding: 10px;
  }
  .counts {
    display: flex;
    gap: 8px;
    font-size: 12px;
    color: var(--ink-3);
    white-space: nowrap;
  }
  .bad {
    color: var(--bad);
    font-weight: 600;
  }
  .warn {
    color: var(--warn);
    font-weight: 600;
  }
  .tool {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border: 0;
    border-radius: 3px;
    background: transparent;
    color: var(--ink-2);
  }
  .tool:hover {
    background: var(--surface-3);
  }
  .only {
    display: flex;
    align-items: center;
    gap: 5px;
    font-size: 12px;
    color: var(--ink-2);
    white-space: nowrap;
  }
  table {
    width: 100%;
  }
  th {
    text-align: left;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-3);
    white-space: nowrap;
  }
  td {
    vertical-align: middle;
  }
  tr.unset td:not(.lim) {
    color: var(--ink-3);
  }
  tr.fail td {
    background: var(--bad-soft);
  }
  .st {
    width: 1%;
    font-weight: 600;
    white-space: nowrap;
  }
  .st.pass {
    color: var(--good);
  }
  .st.warn {
    color: var(--warn);
  }
  .st.fail {
    color: var(--bad);
  }
  .n {
    text-align: right;
    white-space: nowrap;
  }
  .now {
    font-variant-numeric: tabular-nums;
    font-weight: 600;
  }
  .lim {
    white-space: nowrap;
  }
  .lim .input {
    width: 70px;
    text-align: right;
  }
  .u {
    font-size: 12px;
    color: var(--ink-3);
    white-space: nowrap;
    padding-left: 0;
  }
  .label {
    white-space: nowrap;
  }
  .margin {
    white-space: nowrap;
    font-size: 12.5px;
  }
  .sub {
    font-size: 11.5px;
    color: var(--ink-3);
  }
  .g {
    width: 124px;
  }
  .problems {
    margin: 8px 10px;
    padding: 6px 10px;
    border-radius: var(--radius);
    background: var(--warn-soft);
    font-size: 12.5px;
  }
  .foot {
    margin: 6px 10px 10px;
    font-size: 12px;
  }
</style>
