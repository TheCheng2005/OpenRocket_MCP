<script lang="ts">
  import { choices, choose, num, pretty, toShown, toSi, unitOf, type Dim } from "../units.svelte";

  // A number in the chosen unit, with the unit next to it (changing it changes that quantity's unit everywhere,
  // as in OpenRocket). Typing a unit ("3 in") also works; the engine reads it.
  let {
    value,
    dim,
    disabled = false,
    commit,
  }: { value: number | null; dim: Dim; disabled?: boolean; commit: (v: number | string) => void } = $props();

  let unit = $derived(unitOf(dim));
  let shown = $derived(value === null || value === undefined ? "" : num(toShown(value, dim, unit), 4).replace(/,/g, ""));
  let text = $state("");
  let editing = $state(false);

  $effect(() => {
    if (!editing) text = shown;
  });

  function done() {
    editing = false;
    const t = text.trim();
    if (t === shown || t === "") {
      text = shown;
      return;
    }
    if (/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t)) {
      commit(toSi(parseFloat(t), dim, unit));
    } else {
      commit(t); // with a unit, or an expression the engine explains if it cannot read it
    }
  }
</script>

<div class="q" class:disabled>
  <input
    class="input num"
    {disabled}
    bind:value={text}
    onfocus={() => (editing = true)}
    onblur={done}
    onkeydown={(e) => {
      if (e.key === "Enter") (e.target as HTMLInputElement).blur();
      if (e.key === "Escape") {
        text = shown;
        editing = false;
        (e.target as HTMLInputElement).blur();
      }
    }}
  />
  <select class="unit" value={unit.unit} onchange={(e) => choose(dim, (e.target as HTMLSelectElement).value)} tabindex="-1">
    {#each choices(dim) as u (u.unit)}
      <option value={u.unit}>{pretty(u.unit)}</option>
    {/each}
  </select>
</div>

<style>
  .q {
    display: flex;
    min-width: 0;
  }
  .input {
    flex: 1;
    min-width: 0;
    width: 100%;
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
    text-align: right;
  }
  .unit {
    flex: none;
    width: 64px;
    height: 32px;
    border: 1px solid var(--line);
    border-left: 0;
    border-radius: 0 7px 7px 0;
    background: var(--surface-2);
    color: var(--ink-2);
    font-size: 12.5px;
    padding: 0 4px;
  }
  .disabled .input {
    background: var(--surface-2);
    color: var(--ink-3);
  }
</style>
