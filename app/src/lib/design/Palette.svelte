<script lang="ts">
  import { ChevronDown } from "@lucide/svelte";
  import { app, addPart, placement, findNode } from "../state.svelte";
  import ComponentIcon from "../ComponentIcon.svelte";

  const KEY = "rocketmaxxing.palette";
  let open = $state((() => {
    try {
      return localStorage.getItem(KEY) !== "closed";
    } catch {
      return true;
    }
  })());

  function toggle() {
    open = !open;
    try {
      localStorage.setItem(KEY, open ? "open" : "closed");
    } catch {
      /* not kept */
    }
  }

  // OpenRocket's add-component buttons, in the same families, with bigger pictures.
  const GROUPS: { title: string; parts: [string, string][] }[] = [
    { title: "Airframe", parts: [["NoseCone", "Nose cone"], ["BodyTube", "Body tube"], ["Transition", "Transition"]] },
    { title: "Fins", parts: [["TrapezoidFinSet", "Trapezoidal"], ["EllipticalFinSet", "Elliptical"], ["FreeformFinSet", "Freeform"], ["TubeFinSet", "Tube fins"]] },
    { title: "Outside", parts: [["LaunchLug", "Launch lug"], ["RailButton", "Rail button"]] },
    { title: "Inside", parts: [["InnerTube", "Inner tube"], ["TubeCoupler", "Coupler"], ["CenteringRing", "Centering ring"], ["Bulkhead", "Bulkhead"], ["EngineBlock", "Engine block"]] },
    { title: "Recovery & mass", parts: [["Parachute", "Parachute"], ["Streamer", "Streamer"], ["ShockCord", "Shock cord"], ["MassComponent", "Mass"]] },
    { title: "Assemblies", parts: [["AxialStage", "Stage"], ["ParallelStage", "Boosters"], ["PodSet", "Pods"]] },
  ];

  let target = $derived(findNode(app.design?.tree, app.selected)?.name ?? "");
  // Re-evaluate when the tree or the selection changes.
  let can = $derived.by(() => {
    void app.design?.tree;
    void app.selected;
    const m: Record<string, string | null> = {};
    for (const g of GROUPS) {
      for (const [t] of g.parts) {
        const at = placement(t);
        m[t] = at ? (findNode(app.design?.tree, at.parent)?.name ?? null) : null;
      }
    }
    return m;
  });
</script>

<div class="palette">
  <button class="hint" onclick={toggle} aria-expanded={open}>
    <span>Add a part to <b>{target}</b> (or after it)</span>
    <span class="chev" class:shut={!open}><ChevronDown size={16} /></span>
  </button>
  {#if open}
  <div class="groups">
    {#each GROUPS as g (g.title)}
      <div class="group">
        <div class="gt">{g.title}</div>
        <div class="tiles">
          {#each g.parts as [type, label] (type)}
            <button
              class="tile"
              disabled={!can[type]}
              title={can[type] ? `Add a ${label.toLowerCase()} to ${can[type]}` : `A ${label.toLowerCase()} cannot go here; select another part`}
              onclick={() => addPart(type)}
            >
              <ComponentIcon {type} size={32} />
              <span>{label}</span>
            </button>
          {/each}
        </div>
      </div>
    {/each}
  </div>
  {/if}
</div>

<style>
  .palette {
    padding: 6px 8px 8px;
  }
  .hint {
    width: 100%;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border: 0;
    background: transparent;
    padding: 0 4px;
    font-size: 12px;
    color: var(--ink-3);
    margin: 0 0 4px;
  }
  .chev {
    display: flex;
    transition: transform 0.12s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .hint b {
    color: var(--ink-2);
  }
  .groups {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 0;
  }
  .group {
    flex: none;
    padding: 0 6px;
    border-left: 1px solid var(--line);
  }
  .group:first-child {
    border-left: 0;
  }
  .gt {
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--ink-3);
    margin: 0 2px 4px;
  }
  .tiles {
    display: flex;
    gap: 4px;
  }
  .tile {
    width: 62px;
    height: 60px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    border: 1px solid transparent;
    border-radius: 10px;
    background: transparent;
    font-size: 10.5px;
    color: var(--ink-2);
    line-height: 1.05;
    text-align: center;
  }
  .tile:hover:not(:disabled) {
    background: var(--accent-soft);
    border-color: var(--accent);
    color: var(--ink);
  }
  .tile:disabled {
    opacity: 0.3;
  }
</style>
