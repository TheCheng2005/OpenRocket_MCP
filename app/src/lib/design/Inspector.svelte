<script lang="ts">
  import { ChevronDown, TriangleAlert } from "@lucide/svelte";
  import { app, editField } from "../state.svelte";
  import { fmt } from "../units.svelte";
  import { isOpen, toggle } from "../panels.svelte";
  import ComponentIcon from "../ComponentIcon.svelte";
  import Field from "./Field.svelte";

  // Sections that start folded: rarely changed.
  const FOLDED = new Set(["Overrides", "Comment", "Shoulder", "Shoulders", "Fin tabs"]);

  let ed = $derived(app.editor);
  let sections = $derived(ed ? ed.sections.filter((s) => s.title !== "General" && s.title !== "Design") : []);
  let rocketFields = $derived(ed?.type === "Rocket" ? (ed.sections.find((s) => s.title === "Design")?.fields ?? []).filter((f) => f.key !== "name") : []);
  let name = $state("");
  $effect(() => {
    name = ed?.name ?? "";
  });
</script>

{#if ed}
  <div class="head">
    <ComponentIcon type={ed.type} size={34} />
    <div class="who">
      <input
        class="name"
        bind:value={name}
        onblur={() => name !== ed.name && editField("name", name)}
        onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
        aria-label="Name"
      />
      <div class="type">
        {ed.label}{#if ed.componentMass !== undefined && ed.type !== "Rocket"}, {fmt(ed.componentMass, "MASS")}{#if ed.mass !== undefined && Math.abs(ed.mass - ed.componentMass) > 1e-6}&nbsp;({fmt(ed.mass, "MASS")} with contents){/if}{/if}
      </div>
    </div>
  </div>
  {#if ed.warning}
    <div class="warning"><TriangleAlert size={14} /> <span>{ed.warning}</span></div>
  {/if}
  <div class="sections">
    {#if rocketFields.length}
      <div class="fields">
        {#each rocketFields as f (f.key)}<Field {f} />{/each}
      </div>
    {/if}
    {#each sections as s (s.title)}
      {@const id = "prop." + s.title}
      {@const open = isOpen(id, !FOLDED.has(s.title))}
      <section class="sec">
        <button class="st" onclick={() => toggle(id, !FOLDED.has(s.title))} aria-expanded={open}>
          <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
          {s.title}
        </button>
        {#if open}
          <div class="fields">
            {#each s.fields as f (f.key)}<Field {f} />{/each}
          </div>
        {/if}
      </section>
    {/each}
  </div>
{/if}

<style>
  .head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    border-bottom: 1px solid var(--line);
  }
  .who {
    min-width: 0;
    flex: 1;
  }
  .name {
    width: 100%;
    font-size: 14px;
    font-weight: 600;
    border: 1px solid transparent;
    border-radius: var(--radius);
    padding: 1px 4px;
    margin-left: -5px;
    background: transparent;
  }
  .name:hover {
    border-color: var(--line);
  }
  .name:focus {
    outline: none;
    border-color: var(--select);
    background: var(--surface);
  }
  .type {
    font-size: 12px;
    color: var(--ink-3);
  }
  .warning {
    display: flex;
    gap: 6px;
    align-items: flex-start;
    padding: 6px 10px;
    border-bottom: 1px solid var(--line);
    background: var(--warn-soft);
    font-size: 12px;
  }
  .warning :global(svg) {
    flex: none;
    margin-top: 2px;
    color: var(--warn);
  }
  .sections {
    flex: 1;
    overflow: auto;
    padding-bottom: 10px;
  }
  .sec {
    border-bottom: 1px solid var(--line);
  }
  .st {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 4px;
    border: 0;
    background: transparent;
    padding: 6px 8px;
    font-weight: 600;
    color: var(--ink);
    text-align: left;
  }
  .st:hover {
    background: var(--surface-2);
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .fields {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 2px 10px 8px 26px;
  }
  .sections > .fields {
    padding: 8px 10px;
    border-bottom: 1px solid var(--line);
  }
</style>
