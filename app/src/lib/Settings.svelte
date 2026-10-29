<script lang="ts">
  import { X } from "@lucide/svelte";
  import { app, setSystem } from "./state.svelte";
  import { units, choices, choose, unitOf, pretty, type Dim } from "./units.svelte";
  import { prefs, setTheme, type Theme } from "./prefs.svelte";

  let { close }: { close: () => void } = $props();

  const QUANTITIES: [Dim, string][] = [
    ["LENGTH", "Length (parts)"], ["DISTANCE", "Distance and altitude"], ["VELOCITY", "Speed"],
    ["ACCELERATION", "Acceleration"], ["MASS", "Mass"], ["FORCE", "Force and thrust"], ["IMPULSE", "Impulse"],
    ["TIME", "Time"], ["ANGLE", "Angle"], ["AREA", "Area"], ["VOLUME", "Volume"], ["PRESSURE", "Pressure"],
    ["DENSITY", "Density"], ["TEMPERATURE", "Temperature"],
  ];
  const THEMES: [Theme, string][] = [["system", "Same as the system"], ["light", "Light"], ["dark", "Dark"]];
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && close()} />

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="dlg" role="dialog" aria-label="Settings">
    <header>
      <h3>Settings</h3>
      <button class="btn ghost" onclick={close} aria-label="Close"><X size={16} /></button>
    </header>

    <div class="content">
      <h4>Units</h4>
      <div class="row">
        <span>Default units</span>
        <div class="seg">
          <button class:on={units.system === "metric"} onclick={() => setSystem("metric")}>Metric</button>
          <button class:on={units.system === "imperial"} onclick={() => setSystem("imperial")}>Imperial</button>
        </div>
      </div>
      <p class="note">Choosing metric or imperial resets the units below.</p>
      <div class="units">
        {#each QUANTITIES as [dim, label] (dim)}
          <label class="row">
            <span>{label}</span>
            <select class="select" value={unitOf(dim).unit} onchange={(e) => choose(dim, (e.target as HTMLSelectElement).value)}>
              {#each choices(dim) as u (u.unit)}<option value={u.unit}>{pretty(u.unit)}</option>{/each}
            </select>
          </label>
        {/each}
      </div>

      <h4>Appearance</h4>
      <div class="row">
        <span>Theme</span>
        <select class="select" value={prefs.theme} onchange={(e) => setTheme((e.target as HTMLSelectElement).value as Theme)}>
          {#each THEMES as [t, label] (t)}<option value={t}>{label}</option>{/each}
        </select>
      </div>

      <h4>Files</h4>
      <div class="row"><span>Designs folder</span><code>{app.session?.workspace}</code></div>
      <p class="note">RocketMaxxing {app.session?.version}, engine {app.session?.engineVersion}, OpenRocket 24.12</p>
    </div>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 30%);
    display: grid;
    place-items: center;
    z-index: 40;
  }
  .dlg {
    width: min(520px, calc(100vw - 32px));
    max-height: calc(100vh - 40px);
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 8px 8px 16px;
    border-bottom: 1px solid var(--line);
  }
  h3 {
    margin: 0;
    font-size: 15px;
  }
  .content {
    overflow: auto;
    padding: 4px 16px 16px;
  }
  h4 {
    margin: 16px 0 6px;
    font-size: 13px;
  }
  .row {
    display: grid;
    grid-template-columns: 1fr 200px;
    align-items: center;
    gap: 10px;
    min-height: 32px;
    color: var(--ink-2);
  }
  .units {
    display: flex;
    flex-direction: column;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .seg button {
    flex: 1;
    border: 0;
    background: var(--surface);
    padding: 5px 0;
    color: var(--ink-2);
  }
  .seg button.on {
    background: var(--select);
    color: #fff;
  }
  .note {
    margin: 4px 0;
    font-size: 12px;
    color: var(--ink-3);
  }
  code {
    font-size: 12px;
    overflow-wrap: anywhere;
  }
</style>
