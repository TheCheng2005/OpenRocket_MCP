<script lang="ts">
  import { ChevronDown, TriangleAlert } from "@lucide/svelte";
  import { app, editField } from "../state.svelte";
  import { fmt } from "../units.svelte";
  import ComponentIcon from "../ComponentIcon.svelte";
  import Field from "./Field.svelte";

  const STORE = "rocketmaxxing.closedSections";
  let closed = $state<Record<string, boolean>>(
    (() => {
      try {
        return JSON.parse(localStorage.getItem(STORE) ?? "") ?? { Overrides: true, Comment: true };
      } catch {
        return { Overrides: true, Comment: true, Shoulders: true };
      }
    })(),
  );

  function toggle(title: string) {
    closed[title] = !closed[title];
    try {
      localStorage.setItem(STORE, JSON.stringify(closed));
    } catch {
      /* not kept */
    }
  }

  let ed = $derived(app.editor);
  let sections = $derived(ed ? ed.sections.filter((s) => s.title !== "General" && s.title !== "Design") : []);
  let nameField = $derived(ed?.sections.find((s) => s.title === "General" || s.title === "Design"));
  let name = $state("");
  $effect(() => {
    name = ed?.name ?? "";
  });
</script>

{#if ed}
  <div class="head">
    <ComponentIcon type={ed.type} size={44} />
    <div class="who">
      <input
        class="name"
        bind:value={name}
        onblur={() => name !== ed.name && editField("name", name)}
        onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
        aria-label="Name"
      />
      <div class="type">
        {ed.label}
        {#if ed.componentMass !== undefined && ed.type !== "Rocket"}
          · {fmt(ed.componentMass, "MASS")}{#if ed.mass !== undefined && Math.abs(ed.mass - ed.componentMass) > 1e-6}&nbsp;(with parts inside {fmt(ed.mass, "MASS")}){/if}
        {/if}
      </div>
    </div>
  </div>
  {#if ed.warning}
    <div class="warning"><TriangleAlert size={16} /> {ed.warning}</div>
  {/if}
  <div class="sections">
    {#if ed.type === "Rocket" && nameField}
      <section class="sec">
        <div class="fields">
          {#each nameField.fields.filter((f) => f.key !== "name") as f (f.key)}
            <Field {f} />
          {/each}
        </div>
      </section>
    {/if}
    {#each sections as s (s.title)}
      <section class="sec">
        <button class="st" onclick={() => toggle(s.title)} aria-expanded={!closed[s.title]}>
          <span>{s.title}</span>
          <span class="chev" class:shut={closed[s.title]}><ChevronDown size={16} /></span>
        </button>
        {#if !closed[s.title]}
          <div class="fields">
            {#each s.fields as f (f.key)}
              <Field {f} />
            {/each}
          </div>
        {/if}
      </section>
    {/each}
    {#if ed.type === "Rocket"}
      <p class="muted tip">Select a part in the list or click it in the view to edit it. Add parts from the palette under the view.</p>
    {/if}
  </div>
{/if}

<style>
  .head {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px;
    border-bottom: 1px solid var(--line);
  }
  .who {
    min-width: 0;
    flex: 1;
  }
  .name {
    width: 100%;
    font-size: 17px;
    font-weight: 650;
    border: 1px solid transparent;
    border-radius: 6px;
    padding: 2px 6px;
    margin-left: -6px;
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
    font-size: 12.5px;
    color: var(--ink-3);
  }
  .warning {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    margin: 10px 12px 0;
    padding: 8px 10px;
    border-radius: 8px;
    background: var(--warn-soft);
    color: var(--ink);
    font-size: 12.5px;
  }
  .sections {
    flex: 1;
    overflow: auto;
    padding: 6px 12px 16px;
  }
  .sec {
    border-bottom: 1px solid var(--line);
    padding: 4px 0 8px;
  }
  .sec:last-child {
    border-bottom: 0;
  }
  .st {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border: 0;
    background: transparent;
    padding: 8px 2px;
    font-weight: 650;
    font-size: 13.5px;
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.12s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .fields {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 2px 2px 4px;
  }
  .tip {
    font-size: 13px;
    margin-top: 16px;
  }
</style>
