<script lang="ts">
  import { BookOpen, ChevronDown, TriangleAlert } from "@lucide/svelte";
  import { app, editField, act } from "../state.svelte";
  import { fmt } from "../units.svelte";
  import { isOpen, toggle } from "../panels.svelte";
  import ComponentIcon from "../ComponentIcon.svelte";
  import Field from "./Field.svelte";
  import FinShape from "./FinShape.svelte";
  import CatalogPicker from "./CatalogPicker.svelte";

  // Sections that start folded: rarely changed.
  const FOLDED = new Set(["Overrides", "Comment", "Shoulder", "Shoulders", "Fin tabs", "Appearance"]);
  const FINS = new Set(["TrapezoidFinSet", "EllipticalFinSet", "FreeformFinSet"]);

  let catalog = $state(false);
  // The colour the 3-D view gives the part when it has no paint of its own.
  let shownColor = $derived(app.model?.parts.find((p) => p.id === ed?.id)?.color ?? "#bbbbbb");

  function paint(color: string, shine?: number) {
    act("app_appearance", { component: ed!.id, color, ...(shine === undefined ? {} : { shine }) });
  }

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
  {#if ed.presetType}
    <div class="catalog">
      <span class="muted">{ed.preset ? "Catalogue part: " + ed.preset : "Not a catalogue part"}</span>
      <button class="btn" onclick={() => (catalog = true)}><BookOpen size={14} /> Catalogue…</button>
    </div>
  {/if}
  <div class="sections">
    {#if rocketFields.length}
      <div class="fields">
        {#each rocketFields as f (f.key)}<Field {f} />{/each}
      </div>
    {/if}
    {#if FINS.has(ed.type)}
      {@render folding("Outline", fin)}
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
    {#if ed.type !== "Rocket"}
      {@render folding("Appearance", look)}
    {/if}
  </div>
  {#if catalog}<CatalogPicker component={ed.id} close={() => (catalog = false)} />{/if}
{/if}

{#snippet folding(title: string, body: import("svelte").Snippet)}
  {@const id = "prop." + title}
  {@const open = isOpen(id, !FOLDED.has(title))}
  <section class="sec">
    <button class="st" onclick={() => toggle(id, !FOLDED.has(title))} aria-expanded={open}>
      <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
      {title}
    </button>
    {#if open}<div class="fields">{@render body()}</div>{/if}
  </section>
{/snippet}

{#snippet fin()}
  {#key ed!.id}<FinShape component={ed!.id} type={ed!.type} />{/key}
{/snippet}

{#snippet look()}
  <div class="row">
    <span class="label">Paint</span>
    <div class="control">
      <input type="color" class="swatch" value={ed!.paint ?? shownColor} onchange={(e) => paint(e.currentTarget.value)} aria-label="Paint colour" />
      {#if ed!.paint}<button class="link" onclick={() => paint("")}>Default</button>{:else}<span class="muted small">default</span>{/if}
    </div>
  </div>
  <div class="row">
    <span class="label">Shine</span>
    <input type="range" min="0" max="1" step="0.05" value={ed!.shine ?? 0.3} disabled={!ed!.paint}
      onchange={(e) => paint(ed!.paint!, Number(e.currentTarget.value))} aria-label="Shine" />
  </div>
{/snippet}

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
  .catalog {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 10px;
    border-bottom: 1px solid var(--line);
    font-size: 12px;
  }
  .catalog .btn {
    flex: none;
  }
  .row {
    display: grid;
    grid-template-columns: 44% 1fr;
    align-items: center;
    gap: 6px;
    min-height: 28px;
  }
  .row .label {
    color: var(--ink-2);
  }
  .control {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .swatch {
    width: 44px;
    height: 24px;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: 4px;
    background: transparent;
  }
  .link {
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0;
  }
  .small {
    font-size: 12px;
  }
  .sections > .fields {
    padding: 8px 10px;
    border-bottom: 1px solid var(--line);
  }
</style>
