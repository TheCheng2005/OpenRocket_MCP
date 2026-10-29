<script lang="ts">
  import { FilePlus2, FolderOpen, Save, Undo2, Redo2, Settings as Gear, Loader } from "@lucide/svelte";
  import { app, newDesign, openDialog, save, undo, redo, selectConfig } from "./state.svelte";
  import Settings from "./Settings.svelte";

  let settings = $state(false);
</script>

<header>
  <div class="brand">
    <img src="./favicon.svg" alt="" width="20" height="20" />
    <span>RocketMaxxing</span>
  </div>

  <div class="group">
    <button class="btn ghost" onclick={newDesign} title="New design"><FilePlus2 size={16} /> New</button>
    <button class="btn ghost" onclick={openDialog} title="Open (Ctrl+O)"><FolderOpen size={16} /> Open</button>
    <button class="btn ghost" onclick={() => save(false)} disabled={!app.design} title="Save (Ctrl+S), save as (Ctrl+Shift+S)">
      <Save size={16} /> Save
    </button>
  </div>
  <span class="sep"></span>
  <div class="group">
    <button class="btn ghost icon" onclick={undo} disabled={!app.design?.canUndo} title="Undo (Ctrl+Z)"><Undo2 size={16} /></button>
    <button class="btn ghost icon" onclick={redo} disabled={!app.design?.canRedo} title="Redo (Ctrl+Y)"><Redo2 size={16} /></button>
  </div>

  {#if app.design}
    <div class="title" title={app.design.path ?? "Not saved yet"}>
      <span class="name">{app.design.name}</span>
      {#if !app.design.saved}<span class="edited">(edited)</span>{/if}
    </div>
  {/if}

  <div class="spacer"></div>

  {#if app.busy > 0}<span class="busy" title="Working"><Loader size={16} /></span>{/if}

  {#if app.design}
    <label class="config">
      <span>Configuration</span>
      <select class="select" value={app.design.selected} onchange={(e) => selectConfig((e.target as HTMLSelectElement).value)}>
        {#each app.design.configurations as c (c.id)}
          <option value={c.id}>{c.name}</option>
        {/each}
      </select>
    </label>
  {/if}

  <button class="btn ghost icon" onclick={() => (settings = true)} title="Settings: units, theme"><Gear size={17} /></button>
</header>

{#if settings}
  <Settings close={() => (settings = false)} />
{/if}

<style>
  header {
    height: 40px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 0 8px;
    background: var(--surface);
    border-bottom: 1px solid var(--line);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 7px;
    font-weight: 600;
    padding-right: 10px;
    margin-right: 2px;
    border-right: 1px solid var(--line);
  }
  .group {
    display: flex;
    gap: 1px;
  }
  .sep {
    width: 1px;
    height: 20px;
    background: var(--line);
  }
  .icon {
    padding: 0 7px;
  }
  .title {
    display: flex;
    align-items: baseline;
    gap: 6px;
    min-width: 0;
    margin-left: 8px;
  }
  .name {
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .edited {
    color: var(--ink-3);
    white-space: nowrap;
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
    gap: 6px;
    color: var(--ink-3);
  }
  .config .select {
    max-width: 240px;
    color: var(--ink);
  }
  @media (max-width: 1200px) {
    .config > span {
      display: none;
    }
  }
</style>
