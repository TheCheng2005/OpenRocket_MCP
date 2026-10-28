<script lang="ts">
  import { app } from "../state.svelte";
  import { fmt, num, split } from "../units.svelte";

  let st = $derived(app.design?.stability ?? {});
  let f = $derived(app.flight);
  let margin = $derived(st.margin ?? null);
  // Usual club guidance: under 1 calibre is unstable, over about 3 is over-stable (weathercocks).
  let level = $derived(margin === null ? "none" : margin < 1 ? "bad" : margin < 1.5 || margin > 3.5 ? "warn" : "good");
</script>

{#snippet big(p: [string, string])}
  <div class="v">{p[0]} <small>{p[1]}</small></div>
{/snippet}

<div class="stats">
  <div class="tile stab {level}" title="Static margin at Mach 0.3, from the centre of gravity to the centre of pressure, in body diameters">
    <div class="k">Stability</div>
    <div class="v">{margin === null ? "–" : num(margin, 3)} <small>cal</small></div>
    <div class="s">CG {fmt(st.cg, "LENGTH")} · CP {fmt(st.cp, "LENGTH")}</div>
  </div>
  <div class="tile">
    <div class="k">Mass</div>
    {@render big(split(st.mass, "MASS"))}
    <div class="s">burnout {fmt(st.massBurnout, "MASS")}</div>
  </div>
  <div class="tile">
    <div class="k">Length</div>
    {@render big(split(st.length, "LENGTH"))}
    <div class="s">Ø {fmt(st.diameter, "LENGTH")}</div>
  </div>
  <div class="tile flight" class:stale={app.flying}>
    <div class="k">Apogee</div>
    {#if f?.noMotors}<div class="v small">no motor</div>{:else}{@render big(split(f?.apogee, "DISTANCE"))}{/if}
    <div class="s">{f?.timeToApogee !== undefined ? "at " + fmt(f.timeToApogee, "TIME") : ""}</div>
  </div>
  <div class="tile flight" class:stale={app.flying}>
    <div class="k">Max speed</div>
    {@render big(split(f?.maxVelocity, "VELOCITY"))}
    <div class="s">{f?.maxMach !== undefined ? "Mach " + num(f.maxMach, 3) : ""}</div>
  </div>
  <div class="tile flight" class:stale={app.flying}>
    <div class="k">Max accel.</div>
    {@render big(split(f?.maxAcceleration, "ACCELERATION"))}
    <div class="s">{f?.maxAcceleration !== undefined ? num(f.maxAcceleration / 9.80665, 3) + " G" : ""}</div>
  </div>
  <div class="tile flight" class:stale={app.flying}>
    <div class="k">Rail exit</div>
    {@render big(split(f?.railExitVelocity, "VELOCITY"))}
    <div class="s">{f?.minStability !== undefined ? "min stab. " + num(f.minStability, 3) + " cal" : ""}</div>
  </div>
</div>
{#if f?.problems?.length || st.warnings?.length}
  <div class="problems">
    {#each [...(f?.problems ?? []), ...(st.warnings ?? [])] as p}<span>⚠ {p}</span>{/each}
  </div>
{/if}

<style>
  .stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
    gap: 6px;
    flex: none;
  }
  .tile {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 6px 9px;
    min-width: 0;
  }
  .k {
    font-size: 10.5px;
    font-weight: 650;
    color: var(--ink-3);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  .v {
    font-size: 16px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .v small {
    font-size: 13px;
    color: var(--ink-3);
  }
  .s {
    font-size: 11px;
    color: var(--ink-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    min-height: 16px;
  }
  .stab.good {
    border-color: var(--good);
    background: var(--good-soft);
  }
  .stab.warn {
    border-color: var(--warn);
    background: var(--warn-soft);
  }
  .stab.bad {
    border-color: var(--bad);
    background: var(--bad-soft);
  }
  .v.small {
    font-size: 15px;
    color: var(--ink-3);
    padding-top: 3px;
  }
  .flight.stale .v {
    opacity: 0.5;
  }
  .problems {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 14px;
    font-size: 12px;
    color: var(--warn);
    padding: 0 4px;
  }
</style>
