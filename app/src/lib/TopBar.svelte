<script lang="ts">
  import { FilePlus2, FolderOpen, Save, Undo2, Redo2, Moon, Sun, Loader } from "@lucide/svelte";
  import { app, newDesign, openDialog, save, undo, redo, selectConfig, setSystem } from "./state.svelte";
  import { units } from "./units.svelte";

  let dark = $state(document.documentElement.dataset.theme === "dark" ||
    (!document.documentElement.dataset.theme && matchMedia("(prefers-color-scheme: dark)").matches));

  function toggleTheme() {
    dark = !dark;
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    try {
      localStorage.setItem("rocketmaxxing.theme", dark ? "dark" : "light");
    } catch {
      /* not kept */
    }
  }
</script>

<header>
  <div class="brand">
    <img src="./favicon.svg" alt="" width="28" height="28" />
    <span>Rocket<b>Maxxing</b></span>
  </div>

  <div class="group">
    <button class="btn ghost" onclick={newDesign} title="New rocket"><FilePlus2 size={18} /> New</button>
    <button class="btn ghost" onclick={openDialog} title="Open a design (Ctrl+O)"><FolderOpen size={18} /> Open</button>
    <button class="btn ghost" onclick={() => save(false)} disabled={!app.design} title="Save (Ctrl+S); Shift: save as">
      <Save size={18} /> Save
    </button>
  </div>

  <div class="group">
    <button class="btn ghost icon" onclick={undo} disabled={!app.design?.canUndo} title="Undo (Ctrl+Z)"><Undo2 size={18} /></button>
    <button class="btn ghost icon" onclick={redo} disabled={!app.design?.canRedo} title="Redo (Ctrl+Y)"><Redo2 size={18} /></button>
  </div>

  {#if app.design}
    <div class="title">
      <span class="name">{app.design.name}</span>
      {#if !app.design.saved}<span class="dot" title="Unsaved changes"></span>{/if}
    </div>
  {/if}

  <div class="spacer"></div>

  {#if app.busy > 0}<span class="busy" title="Working"><Loader size={18} /></span>{/if}

  {#if app.design}
    <label class="config">
      <span>Flight configuration</span>
      <select class="select" value={app.design.selected} onchange={(e) => selectConfig((e.target as HTMLSelectElement).value)}>
        {#each app.design.configurations as c (c.id)}
          <option value={c.id}>{c.name}</option>
        {/each}
      </select>
    </label>
  {/if}

  <div class="seg" role="group" aria-label="Units">
    <button class:on={units.system === "metric"} onclick={() => setSystem("metric")}>Metric</button>
    <button class:on={units.system === "imperial"} onclick={() => setSystem("imperial")}>Imperial</button>
  </div>

  <button class="btn ghost icon" onclick={toggleTheme} title="Light / dark">
    {#if dark}<Sun size={18} />{:else}<Moon size={18} />{/if}
  </button>
</header>

<style>
  header {
    height: 56px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0 12px;
    background: var(--surface);
    border-bottom: 1px solid var(--line);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 17px;
    letter-spacing: -0.01em;
    padding-right: 8px;
    margin-right: 4px;
    border-right: 1px solid var(--line);
  }
  .brand b {
    color: var(--accent);
  }
  .group {
    display: flex;
    gap: 2px;
  }
  .icon {
    padding: 0 9px;
  }
  .title {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    margin-left: 6px;
  }
  .name {
    font-weight: 600;
    font-size: 15px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--accent);
    flex: none;
  }
  .spacer {
    flex: 1;
  }
  .busy {
    display: flex;
    color: var(--ink-3);
    animation: spin 1s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .config {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--ink-3);
  }
  @media (max-width: 1400px) {
    .config > span {
      display: none;
    }
  }
  .config .select {
    max-width: 260px;
    font-size: 14px;
    color: var(--ink);
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: 8px;
    overflow: hidden;
  }
  .seg button {
    border: 0;
    background: var(--surface);
    padding: 6px 10px;
    font-size: 13px;
    color: var(--ink-2);
  }
  .seg button.on {
    background: var(--ink);
    color: var(--surface);
  }
</style>
