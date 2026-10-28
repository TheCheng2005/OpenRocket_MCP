<script lang="ts">
  import { app, editField, type FieldDef } from "../state.svelte";
  import { fmt, num, type Dim } from "../units.svelte";
  import QuantityInput from "./QuantityInput.svelte";

  let { f }: { f: FieldDef } = $props();

  let text = $state("");
  $effect(() => {
    text = f.value == null ? "" : String(f.value);
  });

  let materials = $derived(app.session?.materials?.[f.materialType ?? "BULK"] ?? []);

  function commitText() {
    if (text !== String(f.value ?? "")) editField(f.key, text);
  }

  function commitNumber() {
    const t = text.trim();
    if (t === "" || t === String(f.value)) return;
    const v = Number(t);
    editField(f.key, Number.isFinite(v) ? v : t);
  }
</script>

<div class="field" class:wide={f.kind === "longtext" || f.kind === "bool"}>
  {#if f.kind === "bool"}
    <label class="switch">
      <input type="checkbox" checked={!!f.value} onchange={(e) => editField(f.key, (e.target as HTMLInputElement).checked)} />
      <span class="track"><span class="knob"></span></span>
      <span>{f.label}</span>
    </label>
  {:else}
    <label class="label" for={"f-" + f.key} title={f.help ?? ""}>{f.label}{#if f.help}<span class="help">?</span>{/if}</label>
    <div class="control">
      {#if f.kind === "quantity"}
        <QuantityInput value={f.value} dim={f.dim as Dim} disabled={f.isAuto} commit={(v) => editField(f.key, v)} />
        {#if f.auto}
          <label class="auto" title="Let OpenRocket size it from the neighbouring parts">
            <input type="checkbox" checked={f.isAuto} onchange={(e) => editField(f.auto!, (e.target as HTMLInputElement).checked)} />
            Auto
          </label>
        {/if}
      {:else if f.kind === "enum"}
        <select id={"f-" + f.key} class="select" value={f.value} onchange={(e) => editField(f.key, (e.target as HTMLSelectElement).value)}>
          {#each f.options ?? [] as o (o.value)}
            <option value={o.value}>{o.label}</option>
          {/each}
        </select>
      {:else if f.kind === "material"}
        <select id={"f-" + f.key} class="select" value={f.value} onchange={(e) => editField(f.key, (e.target as HTMLSelectElement).value)}>
          {#if !materials.some((m) => m.name === f.value)}<option value={f.value}>{f.value}</option>{/if}
          {#each materials as m (m.name)}
            <option value={m.name}>{m.name} · {fmt(m.density, (f.materialType === "BULK" ? "DENSITY" : null) as Dim | null)}{f.materialType === "SURFACE" ? " kg/m²" : f.materialType === "LINE" ? " kg/m" : ""}</option>
          {/each}
        </select>
      {:else if f.kind === "count"}
        <div class="count">
          <button class="btn" onclick={() => editField(f.key, Math.max(0, (f.value ?? 0) - 1))} aria-label="Fewer">−</button>
          <input id={"f-" + f.key} class="input num" bind:value={text} onblur={commitNumber} onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
          <button class="btn" onclick={() => editField(f.key, (f.value ?? 0) + 1)} aria-label="More">+</button>
        </div>
      {:else if f.kind === "number"}
        <input id={"f-" + f.key} class="input num right" value={f.value == null ? "" : num(f.value, 4)}
          oninput={(e) => (text = (e.target as HTMLInputElement).value)}
          onblur={commitNumber} onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
      {:else if f.kind === "longtext"}
        <textarea id={"f-" + f.key} rows="3" bind:value={text} onblur={commitText}></textarea>
      {:else}
        <input id={"f-" + f.key} class="input" bind:value={text} onblur={commitText} onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
      {/if}
    </div>
  {/if}
</div>

<style>
  .field {
    display: grid;
    grid-template-columns: 42% 1fr;
    align-items: center;
    gap: 8px;
    min-height: 36px;
  }
  .field.wide {
    grid-template-columns: 1fr;
  }
  .label {
    color: var(--ink-2);
    font-size: 13px;
  }
  .help {
    display: inline-grid;
    place-items: center;
    width: 15px;
    height: 15px;
    margin-left: 5px;
    border-radius: 50%;
    background: var(--surface-3);
    color: var(--ink-3);
    font-size: 10px;
    cursor: help;
  }
  .control {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .control > :global(:first-child) {
    flex: 1;
  }
  .select,
  .input {
    width: 100%;
  }
  .right {
    text-align: right;
  }
  .auto {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    color: var(--ink-3);
    flex: none !important;
  }
  .count {
    display: flex;
    gap: 4px;
  }
  .count .btn {
    width: 32px;
    height: 32px;
    justify-content: center;
    padding: 0;
    font-size: 16px;
  }
  .count .input {
    text-align: center;
  }
  textarea {
    width: 100%;
    border: 1px solid var(--line);
    border-radius: 7px;
    padding: 6px 9px;
    background: var(--surface);
    resize: vertical;
  }
  .switch {
    display: flex;
    align-items: center;
    gap: 10px;
    cursor: pointer;
    color: var(--ink-2);
    font-size: 13px;
  }
  .switch input {
    position: absolute;
    opacity: 0;
  }
  .track {
    width: 36px;
    height: 20px;
    border-radius: 10px;
    background: var(--line-strong);
    position: relative;
    transition: background 0.15s;
    flex: none;
  }
  .knob {
    position: absolute;
    top: 2px;
    left: 2px;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    background: #fff;
    transition: transform 0.15s;
  }
  .switch input:checked + .track {
    background: var(--select);
  }
  .switch input:checked + .track .knob {
    transform: translateX(16px);
  }
  .switch input:focus-visible + .track {
    outline: 2px solid var(--select);
  }
</style>
