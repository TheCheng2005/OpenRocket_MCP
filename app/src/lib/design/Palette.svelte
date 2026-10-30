<script lang="ts">
  import { app, addPart, placement, findNode } from "../state.svelte";

  /** While a tile is pointed at, the part the new one would go into shows in the view and the tree. */
  function preview(type: string | null) {
    const at = type ? placement(type) : null;
    app.hover = at && at.parent !== "rocket" ? at.parent : null;
  }
  import { analysis } from "../analysis/store.svelte";
  import ComponentIcon from "../ComponentIcon.svelte";

  // OpenRocket's add-component buttons, in the same groups.
  const GROUPS: { title: string; parts: [string, string][] }[] = [
    { title: "Body", parts: [["NoseCone", "Nose cone"], ["BodyTube", "Body tube"], ["Transition", "Transition"]] },
    { title: "Fins", parts: [["TrapezoidFinSet", "Trapezoidal"], ["EllipticalFinSet", "Elliptical"], ["FreeformFinSet", "Freeform"], ["TubeFinSet", "Tube fins"]] },
    { title: "External", parts: [["LaunchLug", "Launch lug"], ["RailButton", "Rail button"]] },
    { title: "Inner", parts: [["InnerTube", "Inner tube"], ["TubeCoupler", "Coupler"], ["CenteringRing", "Centering ring"], ["Bulkhead", "Bulkhead"], ["EngineBlock", "Engine block"]] },
    { title: "Mass", parts: [["Parachute", "Parachute"], ["Streamer", "Streamer"], ["ShockCord", "Shock cord"], ["MassComponent", "Mass"]] },
    { title: "Assembly", parts: [["AxialStage", "Stage"], ["ParallelStage", "Boosters"], ["PodSet", "Pods"]] },
  ];

  /** A whole avionics bay, laid out by the engine: its form is on the Analysis page. */
  function avionicsBay() {
    analysis.selected = "add_avionics_bay";
    app.page = "analysis";
  }

  // Where each kind of part would go from the current selection (null: nowhere).
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

<div class="groups">
  {#each GROUPS as g (g.title)}
    <div class="group">
      <div class="gt">{g.title}</div>
      <div class="tiles">
        {#each g.parts as [type, label] (type)}
          <button class="tile" disabled={!can[type]} title={can[type] ? `Add to ${can[type]}` : "Can't go here"} onclick={() => addPart(type)}
            onmouseenter={() => can[type] && preview(type)} onmouseleave={() => preview(null)} onfocus={() => can[type] && preview(type)}
            onblur={() => preview(null)}>
            <ComponentIcon {type} size={32} />
            <span>{label}</span>
          </button>
        {/each}
      </div>
    </div>
  {/each}
  <div class="group">
    <div class="gt">Built for you</div>
    <div class="tiles">
      <button class="tile" title="Coupler, bulkheads, altimeters with batteries and switches, charges and tracker, laid out between two tubes" onclick={avionicsBay}>
        <ComponentIcon type="TubeCoupler" size={32} />
        <span>Avionics bay</span>
      </button>
    </div>
  </div>
</div>

<style>
  .groups {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 0;
    padding: 4px 4px 6px;
  }
  .group {
    flex: none;
    padding: 0 4px;
    border-left: 1px solid var(--line);
  }
  .group:first-child {
    border-left: 0;
  }
  .gt {
    font-size: 11.5px;
    color: var(--ink-3);
    margin: 0 2px 2px;
  }
  .tiles {
    display: flex;
    gap: 2px;
  }
  .tile {
    width: 62px;
    height: 58px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    border: 1px solid transparent;
    border-radius: var(--radius);
    background: transparent;
    font-size: 11px;
    color: var(--ink-2);
    line-height: 1.05;
    text-align: center;
  }
  .tile:hover:not(:disabled) {
    background: var(--surface-2);
    border-color: var(--line-strong);
    color: var(--ink);
  }
  .tile:disabled {
    opacity: 0.3;
  }
</style>
