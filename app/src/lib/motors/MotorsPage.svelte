<script lang="ts">
  import { Plus, Copy, PencilLine, Trash2, X, Flame, Umbrella, Layers } from "@lucide/svelte";
  import { untrack } from "svelte";
  import { call } from "../api";
  import { act, app, ask, run, selectConfig } from "../state.svelte";
  import { fmt, num, type Dim } from "../units.svelte";
  import MotorPicker from "./MotorPicker.svelte";
  import QuantityInput from "../design/QuantityInput.svelte";

  // OpenRocket's "Motors & Configuration" tab: one row per flight configuration; motors per mount, recovery
  // deployment per device and separation per stage in three tabs.

  interface Mount {
    id: string;
    name: string;
    stage: string;
    innerDiameter: number;
    motors: Record<string, any>;
  }
  interface Recovery {
    id: string;
    name: string;
    type: string;
    deployment: Record<string, { event: string; altitude: number; delay: number }>;
  }
  interface Stage {
    id: string;
    name: string;
    stageNumber: number;
    separation: Record<string, { event: string; delay: number }>;
  }
  interface Configs {
    configurations: { id: string; name: string; hasMotors: boolean }[];
    selected: string;
    mounts: Mount[];
    recovery: Recovery[];
    stages: Stage[];
  }

  let tab = $state<"motors" | "recovery" | "stages">("motors");
  let data = $state<Configs | null>(null);
  let picking = $state<{ mount: Mount; configuration: string } | null>(null);
  let editing = $state<null | { kind: "deploy" | "sep"; id: string; name: string; configuration: string; event: string; altitude: number; delay: number; all: boolean }>(null);

  async function load() {
    if (!app.design) return;
    data = (await run(() => call<Configs>("app_configs", { designId: app.design!.designId }))) ?? null;
  }

  $effect(() => {
    void app.revision;
    untrack(load); // load() touches other state (the busy counter): only a new revision reloads
  });

  const DEPLOY: Record<string, string> = { APOGEE: "At apogee", ALTITUDE: "At an altitude", EJECTION: "Motor ejection charge",
    LOWER_STAGE_SEPARATION: "When the stage below separates", LAUNCH: "At launch", NEVER: "Never" };
  const SEP: Record<string, string> = { UPPER_IGNITION: "When the stage above ignites", IGNITION: "When this stage's motor ignites",
    BURNOUT: "At this stage's burnout", EJECTION: "At this stage's ejection charge", APOGEE: "At apogee", LAUNCH: "At launch",
    NEVER: "Never" };

  function deployText(d: { event: string; altitude: number; delay: number }) {
    let t = d.event === "ALTITUDE" ? "At " + fmt(d.altitude, "DISTANCE") : DEPLOY[d.event] ?? d.event;
    if (d.delay > 0) t += " + " + num(d.delay, 3) + " s";
    return t;
  }

  async function newConfig() {
    const name = await ask("New flight configuration", "Name (leave as is to name it after its motors)", "");
    await act("flight_configuration", name ? { action: "create", name } : { action: "create" });
  }

  async function rename(c: { id: string; name: string }) {
    const name = await ask("Rename configuration", "Name", c.name);
    if (name) await act("flight_configuration", { action: "rename", configuration: c.id, name });
  }

  async function removeMotor(mount: Mount, configuration: string) {
    await act("app_motor_delay", { mount: mount.id, configuration, remove: true });
  }

  async function setDelay(mount: Mount, configuration: string, v: string) {
    await act("app_motor_delay", v === "plugged" ? { mount: mount.id, configuration, plugged: true } : { mount: mount.id, configuration, ejectionDelay: Number(v) });
  }

  async function saveEdit() {
    const e = editing;
    if (!e) return;
    const configuration = e.all ? "all" : e.configuration;
    if (e.kind === "deploy") {
      await act("set_deployment", { component: e.id, event: e.event.toLowerCase(), altitude: e.altitude, delay: e.delay, configuration });
    } else {
      await act("set_stage_separation", { stage: e.id, event: e.event.toLowerCase(), delay: e.delay, configuration });
    }
    editing = null;
  }

  let stages = $derived((data?.stages ?? []).filter((s) => s.stageNumber > 0));
</script>

<div class="page">
  <div class="top">
    <div class="tabs" role="tablist">
      <button role="tab" aria-selected={tab === "motors"} class:on={tab === "motors"} onclick={() => (tab = "motors")}><Flame size={18} /> Motors</button>
      <button role="tab" aria-selected={tab === "recovery"} class:on={tab === "recovery"} onclick={() => (tab = "recovery")}><Umbrella size={18} /> Recovery</button>
      <button role="tab" aria-selected={tab === "stages"} class:on={tab === "stages"} onclick={() => (tab = "stages")}><Layers size={18} /> Stages</button>
    </div>
    <div class="actions">
      <button class="btn" onclick={newConfig}><Plus size={17} /> New configuration</button>
      <button class="btn" onclick={() => data && act("app_config", { action: "copy", configuration: data.selected })}><Copy size={17} /> Copy</button>
      <button class="btn" onclick={() => data && rename(data.configurations.find((c) => c.id === data!.selected)!)}><PencilLine size={17} /> Rename</button>
      <button class="btn" disabled={(data?.configurations.length ?? 0) < 2} onclick={() => data && act("app_config", { action: "remove", configuration: data.selected })}><Trash2 size={17} /> Delete</button>
    </div>
  </div>

  {#if data}
    <div class="card wrap">
      <table class="grid">
        <thead>
          <tr>
            <th class="cfg">Flight configuration</th>
            {#if tab === "motors"}
              {#each data.mounts as m (m.id)}<th>{m.name}<div class="sub">{m.stage} · Ø {fmt(m.innerDiameter, "LENGTH")}</div></th>{/each}
            {:else if tab === "recovery"}
              {#each data.recovery as r (r.id)}<th>{r.name}<div class="sub">{r.type === "Parachute" ? "Parachute" : "Streamer"}</div></th>{/each}
            {:else}
              {#each stages as s (s.id)}<th>{s.name}<div class="sub">separation</div></th>{/each}
            {/if}
          </tr>
        </thead>
        <tbody>
          {#each data.configurations as c (c.id)}
            <tr class:sel={c.id === data.selected}>
              <td class="cfg">
                <label class="radio">
                  <input type="radio" name="active" checked={c.id === data.selected} onchange={() => selectConfig(c.id)} />
                  <span class="cname">{c.name}</span>
                </label>
              </td>
              {#if tab === "motors"}
                {#each data.mounts as m (m.id)}
                  {@const mo = m.motors[c.id]}
                  <td class="cell">
                    {#if mo?.designation}
                      <div class="motor">
                        <button class="pick" onclick={() => (picking = { mount: m, configuration: c.id })} title="Change the motor">
                          <span class="des">{mo.manufacturer} {mo.designation}</span>
                          <span class="muted">{fmt(mo.totalImpulse, "IMPULSE")} · {fmt(mo.burnTime, "TIME", 3)} burn</span>
                        </button>
                        <select class="select delay" title="Ejection delay" value={mo.plugged ? "plugged" : String(mo.ejectionDelay)} onchange={(e) => setDelay(m, c.id, (e.target as HTMLSelectElement).value)}>
                          {#if !mo.plugged}<option value={String(mo.ejectionDelay)}>{num(mo.ejectionDelay, 3)} s delay</option>{/if}
                          {#each [0, 2, 4, 6, 8, 10, 12, 14, 16, 18] as d}{#if d !== mo.ejectionDelay}<option value={String(d)}>{d} s delay</option>{/if}{/each}
                          <option value="plugged">Plugged</option>
                        </select>
                        <button class="btn ghost x" title="Remove the motor" onclick={() => removeMotor(m, c.id)}><X size={15} /></button>
                      </div>
                    {:else}
                      <button class="empty" onclick={() => (picking = { mount: m, configuration: c.id })}><Plus size={16} /> Choose motor</button>
                    {/if}
                  </td>
                {/each}
                {#if data.mounts.length === 0}<td class="muted">No motor mount yet: on the Design page, open an inner tube or body tube and switch on “This is a motor mount”.</td>{/if}
              {:else if tab === "recovery"}
                {#each data.recovery as r (r.id)}
                  {@const d = r.deployment[c.id]}
                  <td class="cell">
                    <button class="pick" onclick={() => (editing = { kind: "deploy", id: r.id, name: r.name, configuration: c.id, event: d.event, altitude: d.altitude, delay: d.delay, all: false })}>
                      <span class="des">{deployText(d)}</span>
                    </button>
                  </td>
                {/each}
                {#if data.recovery.length === 0}<td class="muted">No parachute or streamer in the design.</td>{/if}
              {:else}
                {#each stages as s (s.id)}
                  {@const sp = s.separation[c.id]}
                  <td class="cell">
                    <button class="pick" onclick={() => (editing = { kind: "sep", id: s.id, name: s.name, configuration: c.id, event: sp.event, altitude: 0, delay: sp.delay, all: false })}>
                      <span class="des">{SEP[sp.event] ?? sp.event}{sp.delay > 0 ? " + " + num(sp.delay, 3) + " s" : ""}</span>
                    </button>
                  </td>
                {/each}
                {#if stages.length === 0}<td class="muted">A single-stage rocket: nothing separates. Add a stage on the Design page for a two-stage rocket.</td>{/if}
              {/if}
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <p class="muted hint">The selected configuration is the one the Design page and the quick flight numbers use. Changes here can be undone (Ctrl+Z).</p>
  {/if}
</div>

{#if picking}
  <MotorPicker mount={picking.mount} configuration={picking.configuration} close={() => (picking = null)} />
{/if}

{#if editing}
  {@const e = editing}
  <div class="scrim" role="presentation" onclick={(ev) => ev.target === ev.currentTarget && (editing = null)}>
    <div class="dialog card">
      <h3>{e.kind === "deploy" ? "Deploy" : "Separate"} {e.name}</h3>
      <label>When
        <select class="select" bind:value={e.event}>
          {#each Object.entries(e.kind === "deploy" ? DEPLOY : SEP) as [k, v]}<option value={k}>{v}</option>{/each}
        </select>
      </label>
      {#if e.kind === "deploy" && e.event === "ALTITUDE"}
        <label>Altitude above the pad <QuantityInput value={e.altitude} dim={"DISTANCE" as Dim} commit={(v) => typeof v === "number" && (e.altitude = v)} /></label>
      {/if}
      <label>Delay <QuantityInput value={e.delay} dim={"TIME" as Dim} commit={(v) => typeof v === "number" && (e.delay = v)} /></label>
      <label class="check"><input type="checkbox" bind:checked={e.all} /> Apply to every flight configuration</label>
      <div class="row">
        <button class="btn" onclick={() => (editing = null)}>Cancel</button>
        <button class="btn primary" onclick={saveEdit}>Apply</button>
      </div>
    </div>
  </div>
{/if}

<style>
  .page {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 10px 14px;
  }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
  }
  .tabs {
    display: flex;
    gap: 4px;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: 12px;
    padding: 4px;
  }
  .tabs button {
    display: flex;
    align-items: center;
    gap: 8px;
    border: 0;
    border-radius: 9px;
    padding: 9px 18px;
    background: transparent;
    font-weight: 600;
    font-size: 14.5px;
    color: var(--ink-2);
  }
  .tabs button.on {
    background: var(--accent-soft);
    color: var(--accent);
  }
  .actions {
    display: flex;
    gap: 6px;
  }
  .wrap {
    overflow: auto;
    min-height: 0;
  }
  th .sub {
    font-weight: 400;
    color: var(--ink-3);
  }
  .cfg {
    width: 260px;
    min-width: 200px;
  }
  .radio {
    display: flex;
    align-items: center;
    gap: 10px;
    cursor: pointer;
  }
  .radio input {
    width: 17px;
    height: 17px;
    accent-color: var(--accent);
  }
  .cname {
    font-weight: 600;
  }
  .cell {
    min-width: 240px;
  }
  .motor {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .pick {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    border: 1px solid var(--line);
    background: var(--surface);
    border-radius: 9px;
    padding: 7px 10px;
    text-align: left;
    min-width: 0;
  }
  .pick:hover {
    border-color: var(--accent);
  }
  .des {
    font-weight: 600;
  }
  .pick .muted {
    font-size: 12px;
  }
  .delay {
    width: 120px;
  }
  .x {
    padding: 0 6px;
  }
  .empty {
    display: flex;
    align-items: center;
    gap: 6px;
    border: 1.5px dashed var(--line-strong);
    background: transparent;
    border-radius: 9px;
    padding: 10px 12px;
    color: var(--ink-3);
    width: 100%;
    font-weight: 600;
  }
  .empty:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
  .hint {
    font-size: 12.5px;
    margin: 0;
  }
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 35%);
    display: grid;
    place-items: center;
    z-index: 30;
  }
  .dialog {
    width: min(440px, calc(100vw - 32px));
    padding: 20px;
    display: flex;
    flex-direction: column;
    gap: 12px;
    box-shadow: var(--shadow-pop);
  }
  .dialog h3 {
    margin: 0 0 4px;
  }
  .dialog label {
    display: flex;
    flex-direction: column;
    gap: 5px;
    font-size: 13px;
    color: var(--ink-2);
  }
  .dialog label.check {
    flex-direction: row;
    align-items: center;
  }
  .row {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
</style>
