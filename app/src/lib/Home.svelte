<script lang="ts">
  import { FilePlus2, FolderOpen, FileClock } from "@lucide/svelte";
  import { app, newDesign, openDialog, openExample, openPath, recent } from "./state.svelte";
  import ComponentIcon from "./ComponentIcon.svelte";

  const recents = recent();

  // A picture for each bundled example, from what it is about.
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
  <section class="hero">
    <h1>Design, fly and check your rocket.</h1>
    <p>OpenRocket's physics with a clearer workspace. Works offline; files stay OpenRocket <code>.ork</code> files.</p>
    <div class="actions">
      <button class="btn primary big" onclick={newDesign}><FilePlus2 size={20} /> New rocket</button>
      <button class="btn big" onclick={openDialog}><FolderOpen size={20} /> Open a design…</button>
    </div>
  </section>

  {#if recents.length}
    <section>
      <h2>Recent</h2>
      <div class="recent">
        {#each recents as p (p)}
          <button class="recent-item" onclick={() => openPath(p)} title={p}>
            <FileClock size={18} />
            <span class="file">{p.split(/[\\/]/).pop()}</span>
            <span class="dir muted">{p.split(/[\\/]/).slice(0, -1).join("/")}</span>
          </button>
        {/each}
      </div>
    </section>
  {/if}

  <section>
    <h2>Examples</h2>
    <div class="examples">
      {#each app.session?.examples ?? [] as ex (ex)}
        <button class="example card" onclick={() => openExample(ex)}>
          <ComponentIcon type={iconFor(ex)} size={44} />
          <span>{ex}</span>
        </button>
      {/each}
    </div>
  </section>
  <p class="foot muted">RocketMaxxing {app.session?.version} · engine {app.session?.engineVersion} on OpenRocket 24.12 · designs folder: {app.session?.workspace}</p>
</div>

<style>
  .home {
    overflow: auto;
    padding: 40px 48px;
    max-width: 1180px;
  }
  .hero h1 {
    font-size: 32px;
    letter-spacing: -0.02em;
    margin: 0 0 8px;
  }
  .hero p {
    color: var(--ink-2);
    font-size: 16px;
    margin: 0 0 22px;
  }
  .hero code {
    background: var(--surface-3);
    padding: 1px 6px;
    border-radius: 5px;
  }
  .actions {
    display: flex;
    gap: 10px;
  }
  h2 {
    font-size: 14px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--ink-3);
    margin: 36px 0 12px;
  }
  .examples {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px;
  }
  .example {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px;
    text-align: left;
    font-weight: 500;
  }
  .example:hover {
    border-color: var(--accent);
  }
  .recent {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .recent-item {
    display: flex;
    align-items: center;
    gap: 10px;
    border: 0;
    background: transparent;
    padding: 8px 10px;
    border-radius: 8px;
    text-align: left;
  }
  .recent-item:hover {
    background: var(--surface-3);
  }
  .file {
    font-weight: 600;
  }
  .dir {
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .foot {
    margin-top: 40px;
    font-size: 12px;
  }
</style>
