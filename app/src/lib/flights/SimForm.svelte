<script lang="ts">
  import { act } from "../state.svelte";
  import type { Dim } from "../units.svelte";
  import QuantityInput from "../design/QuantityInput.svelte";

  // OpenRocket's simulation dialog, "Launch conditions" and "Simulation options", as one form.
  let { sim, configs }: { sim: { index: number; name: string; configuration: string; options: Record<string, any> }; configs: { id: string; name: string }[] } =
    $props();

  function set(key: string, value: unknown) {
    act("app_sim_edit", { simulation: sim.index, key, value });
  }

  const Q: [string, string, Dim][] = [
    ["windSpeed", "Average wind speed", "VELOCITY"],
    ["windDirection", "Wind from (direction)", "ANGLE"],
  ];
  let name = $state("");
  $effect(() => {
    name = sim.name;
  });
  let o = $derived(sim.options);
</script>

<div class="f">
  <h3>Simulation</h3>
  <label class="row"><span>Name</span>
    <input class="input" bind:value={name} onblur={() => name !== sim.name && set("name", name)} onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
  </label>
  <label class="row"><span>Flight configuration</span>
    <select class="select" value={sim.configuration} onchange={(e) => set("configuration", (e.target as HTMLSelectElement).value)}>
      {#each configs as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
    </select>
  </label>

  <h3>Wind</h3>
  {#if o.windProfile}<p class="note">This simulation uses a wind profile by altitude; the speed below scales it from the ground.</p>{/if}
  {#each Q as [k, label, d] (k)}
    <div class="row"><span>{label}</span><QuantityInput value={o[k]} dim={d} commit={(v) => set(k, v)} /></div>
  {/each}
  <div class="row"><span>Turbulence intensity</span>
    <div class="pct">
      <input class="input num" value={Math.round((o.windTurbulence ?? 0) * 1000) / 10}
        onchange={(e) => set("windTurbulence", Number((e.target as HTMLInputElement).value) / 100)} />
      <span class="muted">%</span>
    </div>
  </div>

  <h3>Launch site</h3>
  <div class="row"><span>Altitude above sea level</span><QuantityInput value={o.launchAltitude} dim="DISTANCE" commit={(v) => set("launchAltitude", v)} /></div>
  <div class="row"><span>Latitude</span>
    <input class="input num" value={o.latitude} onchange={(e) => set("latitude", Number((e.target as HTMLInputElement).value))} />
  </div>
  <div class="row"><span>Longitude</span>
    <input class="input num" value={o.longitude} onchange={(e) => set("longitude", Number((e.target as HTMLInputElement).value))} />
  </div>
  <label class="row check"><input type="checkbox" checked={o.isaAtmosphere} onchange={(e) => set("isaAtmosphere", (e.target as HTMLInputElement).checked)} /> Standard atmosphere (ISA)</label>
  {#if !o.isaAtmosphere}
    <div class="row"><span>Temperature</span><QuantityInput value={o.temperature} dim="TEMPERATURE" commit={(v) => set("temperature", v)} /></div>
    <div class="row"><span>Pressure</span><QuantityInput value={o.pressure} dim="PRESSURE" commit={(v) => set("pressure", v)} /></div>
  {/if}

  <h3>Launch rail</h3>
  <div class="row"><span>Rail length</span><QuantityInput value={o.rodLength} dim="LENGTH" commit={(v) => set("rodLength", v)} /></div>
  <div class="row"><span>Angle from vertical</span><QuantityInput value={o.rodAngle} dim="ANGLE" commit={(v) => set("rodAngle", v)} /></div>
  <label class="row check"><input type="checkbox" checked={o.launchIntoWind} onchange={(e) => set("launchIntoWind", (e.target as HTMLInputElement).checked)} /> Point the rail into the wind</label>
  {#if !o.launchIntoWind}
    <div class="row"><span>Rail direction</span><QuantityInput value={o.rodDirection} dim="ANGLE" commit={(v) => set("rodDirection", v)} /></div>
  {/if}

  <h3>Simulation</h3>
  <div class="row"><span>Time step</span><QuantityInput value={o.timeStep} dim="TIME" commit={(v) => set("timeStep", v)} /></div>
</div>

<style>
  .f {
    padding: 4px 14px 14px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  h3 {
    margin: 12px 0 2px;
    font-size: 13.5px;
  }
  .row {
    display: grid;
    grid-template-columns: 46% 1fr;
    align-items: center;
    gap: 8px;
    min-height: 34px;
    font-size: 13px;
    color: var(--ink-2);
  }
  .row.check {
    display: flex;
    gap: 8px;
  }
  .row .input,
  .row .select {
    width: 100%;
  }
  .input.num {
    text-align: right;
  }
  .pct {
    display: flex;
    gap: 6px;
    align-items: center;
  }
  .note {
    font-size: 12px;
    color: var(--ink-3);
    margin: 0;
  }
</style>
