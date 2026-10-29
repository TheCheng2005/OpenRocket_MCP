<script lang="ts">
  import { FilePlus2, FolderOpen, FileText } from "@lucide/svelte";
  import { app, newDesign, openDialog, openExample, openPath, recent } from "./state.svelte";
  import ComponentIcon from "./ComponentIcon.svelte";
  import Panel from "./Panel.svelte";

  const recents = recent();

  function iconFor(name: string) {
    const n = name.toLowerCase();
    if (n.includes("parachute") || n.includes("chute")) return "Parachute";
    if (n.includes("stage") || n.includes("airstart")) return "AxialStage";
    if (n.includes("booster") || n.includes("cluster")) return "ParallelStage";
    if (n.includes("pod")) return "PodSet";
    if (n.includes("tube fin")) return "TubeFinSet";
    if (n.includes("payload")) return "MassComponent";
    if (n.includes("nose")) return "NoseCone";
    if (n.includes("drag")) return "Transition";
    return "Rocket";
  }
</script>

<div class="home">
  <div class="col">
    <Panel id="home.start" title="Start">
      <div class="actions">
        <button class="act" onclick={newDesign}><FilePlus2 size={22} /> <span><b>New design</b><small>An empty rocket</small></span></button>
        <button class="act" onclick={openDialog}><FolderOpen size={22} /> <span><b>Open…</b><small>OpenRocket .ork or RockSim .rkt</small></span></button>
      </div>
    </Panel>
    <Panel id="home.recent" title="Recent">
      {#if recents.length}
        <ul class="list">
          {#each recents as p (p)}
            <li>
              <button onclick={() => openPath(p)} title={p}>
                <FileText size={16} />
                <span class="file">{p.split(/[\\/]/).pop()}</span>
                <span class="dir">{p.split(/[\\/]/).slice(0, -1).join("/")}</span>
              </button>
            </li>
          {/each}
        </ul>
      {:else}
        <p class="empty">None yet.</p>
      {/if}
    </Panel>
  </div>
  <div class="col wide">
    <Panel id="home.examples" title="Example designs">
      <ul class="list examples">
        {#each app.session?.examples ?? [] as ex (ex)}
          <li>
            <button onclick={() => openExample(ex)}>
              <ComponentIcon type={iconFor(ex)} size={28} />
              <span class="file">{ex}</span>
            </button>
          </li>
        {/each}
      </ul>
    </Panel>
  </div>
</div>

<style>
  .home {
    flex: 1;
    overflow: auto;
    display: flex;
    gap: 8px;
    padding: 8px;
    align-items: flex-start;
  }
  .col {
    width: 380px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .col.wide {
    flex: 1;
    max-width: 720px;
  }
  .actions {
    display: flex;
    flex-direction: column;
    padding: 4px;
  }
  .act {
    display: flex;
    align-items: center;
    gap: 12px;
    border: 0;
    background: transparent;
    padding: 8px 10px;
    border-radius: var(--radius);
    text-align: left;
    color: var(--ink-2);
  }
  .act:hover {
    background: var(--surface-2);
    color: var(--ink);
  }
  .act span {
    display: flex;
    flex-direction: column;
  }
  .act b {
    color: var(--ink);
    font-weight: 600;
  }
  .act small {
    color: var(--ink-3);
    font-size: 12px;
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 4px;
  }
  .list button {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    border: 0;
    background: transparent;
    padding: 5px 8px;
    border-radius: var(--radius);
    text-align: left;
    color: var(--ink-2);
  }
  .list button:hover {
    background: var(--surface-2);
  }
  .file {
    color: var(--ink);
    white-space: nowrap;
  }
  .dir {
    font-size: 12px;
    color: var(--ink-3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .examples {
    columns: 2 260px;
  }
  .examples li {
    break-inside: avoid;
  }
  .empty {
    margin: 0;
    padding: 10px 12px;
    color: var(--ink-3);
  }
</style>
