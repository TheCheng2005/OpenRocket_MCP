<script lang="ts">
  import { TriangleAlert } from "@lucide/svelte";
  import { app } from "../state.svelte";
  import { fmt, num } from "../units.svelte";
  import { KINDS } from "../issues/issues.svelte";

  let st = $derived(app.design?.stability ?? {});
  let f = $derived(app.flight);
  let margin = $derived(st.margin ?? null);
  // Against the rule set's minimum (Launch Canada: 1.5 cal); 1 cal when the rule set has none.
  let floor = $derived(st.floor ?? 1);
  let level = $derived(margin === null ? "" : margin < floor ? "bad" : margin < floor + 0.5 ? "warn" : "good");
  // The flight's notes with whose problem each is (physics, output); the stability warnings are physics too.
  let notes = $derived([
    ...app.flightIssues.map((i) => ({ kind: i.kind, severity: i.severity, text: i.text })),
    ...((st.warnings ?? []) as string[]).map((text) => ({ kind: "physics" as const, severity: "warning", text })),
  ]);
</script>

<div class="sum">
  <dl>
    <div title="Static margin at Mach 0.3, launch mass">
      <dt>Stability</dt>
      <dd class={level}>{margin === null ? "–" : num(margin, 3) + " cal"}{#if st.floor}<small>min {num(st.floor, 3)}</small>{/if}</dd>
    </div>
    <div><dt>CG / CP</dt><dd>{fmt(st.cg, "LENGTH")} / {fmt(st.cp, "LENGTH")}</dd></div>
    <div><dt>Mass</dt><dd>{fmt(st.mass, "MASS")}<small>burnout {fmt(st.massBurnout, "MASS")}</small></dd></div>
    <div><dt>Length / Ø</dt><dd>{fmt(st.length, "LENGTH")} / {fmt(st.diameter, "LENGTH")}</dd></div>
  </dl>
  <dl class="flight" class:stale={app.flying} title="Quick simulation of the active configuration">
    {#if f?.noMotors}
      <div><dt>Flight</dt><dd class="none">No motor in this configuration</dd></div>
    {:else}
      <div><dt>Apogee</dt><dd>{fmt(f?.apogee, "DISTANCE")}</dd></div>
      <div><dt>Max speed</dt><dd>{fmt(f?.maxVelocity, "VELOCITY")}{#if f?.maxMach !== undefined}<small>Mach {f.maxMach.toFixed(2)}</small>{/if}</dd></div>
      <div><dt>Max accel.</dt><dd>{fmt(f?.maxAcceleration, "ACCELERATION")}</dd></div>
      <div><dt>Rail exit</dt><dd>{fmt(f?.railExitVelocity, "VELOCITY")}</dd></div>
    {/if}
  </dl>
</div>
{#if notes.length}
  <ul class="notes">
    {#each notes as n}<li class="k-{n.kind} {n.severity}"><TriangleAlert size={13} /> <b>{KINDS[n.kind].label}</b> {n.text}</li>{/each}
  </ul>
{/if}

<style>
  .sum {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 16px;
    padding: 6px 10px;
  }
  dl {
    margin: 0;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 2px 12px;
  }
  dl > div {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  dt {
    font-size: 11.5px;
    color: var(--ink-3);
  }
  dd {
    margin: 0;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-weight: 600;
  }
  dd small {
    margin-left: 6px;
    font-weight: 400;
    color: var(--ink-3);
  }
  dd.good {
    color: var(--good);
  }
  dd.warn {
    color: var(--warn);
  }
  dd.bad {
    color: var(--bad);
  }
  dd.none {
    font-weight: 400;
    color: var(--ink-3);
  }
  .flight {
    border-left: 1px solid var(--line);
    padding-left: 16px;
  }
  .flight.stale dd {
    opacity: 0.5;
  }
  .notes {
    list-style: none;
    margin: 0;
    padding: 0 10px 6px;
    font-size: 12px;
    color: var(--warn);
  }
  .notes li {
    display: flex;
    align-items: baseline;
    gap: 5px;
  }
  .notes li :global(svg) {
    flex: none;
    align-self: center;
  }
  .notes li.error {
    color: var(--bad);
  }
  .notes b {
    flex: none;
    font-size: 10.5px;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
  @media (max-width: 1100px) {
    .sum {
      grid-template-columns: 1fr;
    }
    .flight {
      border-left: 0;
      padding-left: 0;
      border-top: 1px solid var(--line);
      padding-top: 4px;
      margin-top: 4px;
    }
  }
</style>
