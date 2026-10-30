<script lang="ts">
  import { Plus, RotateCcw, Trash2 } from "@lucide/svelte";
  import { label } from "../analysis/text";
  import StdNode from "./StdNode.svelte";
  import { NAMED, at, isNote, isObject, parse, type Json } from "./std";

  // One group of team standards: its values as rows, named tables, lists, notes, and groups inside it.
  let {
    path,
    std,
    set,
  }: {
    path: string[];
    std: { team: Json; merged: Json; defaults: Json };
    set: (path: string[], value: Json) => void;
  } = $props();

  let cur = $derived((at(std.merged, path) ?? {}) as { [k: string]: Json });
  let def = $derived((at(std.defaults, path) ?? {}) as { [k: string]: Json });
  let keys = $derived([...new Set([...Object.keys(def), ...Object.keys(cur)])]);
  let notes = $derived(keys.filter((k) => isNote(k) && typeof (cur[k] ?? def[k]) === "string"));
  let rows = $derived(keys.filter((k) => !isNote(k) && !NAMED.has(k) && !Array.isArray(cur[k] ?? def[k]) && !isObject(cur[k] ?? def[k])));
  let tables = $derived(keys.filter((k) => NAMED.has(k)));
  let lists = $derived(keys.filter((k) => Array.isArray(cur[k] ?? def[k])));
  let groups = $derived(keys.filter((k) => !NAMED.has(k) && isObject(cur[k] ?? def[k])));

  const teamHas = (p: string[]) => at(std.team, p) !== undefined;
  const shown = (v: Json | undefined) => (v === null || v === undefined ? "" : String(v));
  const columnsOf = (items: Json[]) => [...new Set(items.flatMap((i) => (isObject(i) ? Object.keys(i) : [])))];
  let adding = $state<Record<string, string>>({});
</script>

{#each notes as k (k)}<p class="note">{cur[k] ?? def[k]}</p>{/each}

{#each rows as k (k)}
  {@const p = [...path, k]}
  {@const v = cur[k]}
  <div class="row" class:team={teamHas(p)}>
    <span class="name" title={k}>{label(k)}</span>
    {#if typeof (v ?? def[k]) === "boolean"}
      <input type="checkbox" checked={!!v} onchange={(e) => set(p, e.currentTarget.checked)} />
    {:else}
      <input class="input" value={shown(v)} placeholder={def[k] === null || def[k] === "" ? "not set" : ""} onchange={(e) => set(p, parse(e.currentTarget.value, def[k]))} />
    {/if}
    <span class="who">
      {#if teamHas(p)}
        <button class="tool" title="Back to the default ({shown(def[k]) || 'not set'})" onclick={() => set(p, null)}><RotateCcw size={13} /></button>
      {:else}<span class="muted">default</span>{/if}
    </span>
  </div>
{/each}

{#each tables as k (k)}
  {@const entries = Object.entries(isObject(cur[k]) ? cur[k] : {}).filter(([n]) => !isNote(n))}
  {@const cols = columnsOf(entries.map(([, e]) => e)).filter((c) => c !== "source")}
  <div class="block">
    <div class="title">{label(k)}</div>
    <table class="grid">
      <thead><tr><th>Name</th>{#if cols.length}{#each cols as c (c)}<th>{label(c)}</th>{/each}{:else}<th>Value</th>{/if}<th>Source</th><th></th></tr></thead>
      <tbody>
        {#each entries as [n, e] (n)}
          {@const p = [...path, k, n]}
          <tr class:team={teamHas(p)}>
            <td class="key">{n}</td>
            {#if isObject(e)}
              {#each cols as c (c)}
                <td><input class="input" value={shown(e[c])} onchange={(ev) => set([...p, c], parse(ev.currentTarget.value, e[c]))} /></td>
              {/each}
              <td><input class="input src" value={shown(e.source)} onchange={(ev) => set([...p, "source"], parse(ev.currentTarget.value, ""))} /></td>
            {:else}
              <td><input class="input" value={shown(e)} onchange={(ev) => set(p, parse(ev.currentTarget.value, e))} /></td><td></td>
            {/if}
            <td class="acts">
              {#if teamHas(p)}
                <button class="tool" title={at(std.defaults, p) === undefined ? "Remove" : "Back to the default"} onclick={() => set(p, null)}>
                  {#if at(std.defaults, p) === undefined}<Trash2 size={13} />{:else}<RotateCcw size={13} />{/if}
                </button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
    <div class="add">
      <input class="input" placeholder="New name, e.g. 2-56 nylon" bind:value={adding[k]} />
      <button class="btn" disabled={!adding[k]?.trim()} onclick={() => {
        const first = cols[0];
        set([...path, k, adding[k].trim()], first ? { [first]: "" } : "");
        adding[k] = "";
      }}><Plus size={13} /> Add</button>
    </div>
  </div>
{/each}

{#each lists as k (k)}
  {@const items = (Array.isArray(cur[k]) ? cur[k] : []) as Json[]}
  {@const cols = columnsOf(items)}
  {@const p = [...path, k]}
  <div class="block">
    <div class="title">
      {label(k)}
      {#if teamHas(p)}<button class="tool" title="Back to the defaults" onclick={() => set(p, null)}><RotateCcw size={13} /></button>{:else}<span class="muted small">default</span>{/if}
    </div>
    <div class="scroll">
      <table class="grid">
        <thead><tr>{#each cols as c (c)}<th>{label(c)}</th>{/each}<th></th></tr></thead>
        <tbody>
          {#each items as item, i (i)}
            <tr>
              {#each cols as c (c)}
                {@const v = isObject(item) ? item[c] : null}
                <td><input class="input" value={shown(v)} onchange={(ev) => {
                  const next = items.map((x, j) => (j === i && isObject(x) ? { ...x, [c]: parse(ev.currentTarget.value, v) } : x));
                  set(p, next);
                }} /></td>
              {/each}
              <td class="acts"><button class="tool" title="Remove" onclick={() => set(p, items.filter((_, j) => j !== i))}><Trash2 size={13} /></button></td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <button class="btn add1" onclick={() => set(p, [...items, Object.fromEntries(cols.map((c) => [c, null]))])}><Plus size={13} /> Row</button>
  </div>
{/each}

{#each groups as k (k)}
  <div class="group">
    <div class="title">{label(k)}</div>
    <StdNode path={[...path, k]} {std} {set} />
  </div>
{/each}

<style>
  .note {
    margin: 2px 0 8px;
    font-size: 12px;
    color: var(--ink-3);
  }
  .row {
    display: grid;
    grid-template-columns: minmax(160px, 40%) 1fr 70px;
    align-items: center;
    gap: 8px;
    min-height: 30px;
  }
  .row .name {
    color: var(--ink-2);
  }
  .row.team .name::after,
  tr.team .key::after {
    content: "●";
    color: var(--select);
    font-size: 8px;
    margin-left: 5px;
    vertical-align: middle;
  }
  .row input[type="checkbox"] {
    justify-self: start;
    width: 16px;
    height: 16px;
  }
  .who {
    font-size: 11.5px;
  }
  .block,
  .group {
    margin: 10px 0 4px;
  }
  .group {
    padding-left: 12px;
    border-left: 2px solid var(--line);
  }
  .title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
    font-size: 12.5px;
    margin-bottom: 4px;
  }
  .small {
    font-size: 11.5px;
    font-weight: 400;
  }
  .scroll {
    overflow: auto;
  }
  th {
    text-align: left;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--ink-3);
    white-space: nowrap;
  }
  td {
    padding: 1px 3px;
  }
  td .input {
    width: 100%;
    min-width: 70px;
  }
  .src {
    min-width: 160px;
  }
  .key {
    white-space: nowrap;
    font-size: 12.5px;
  }
  .acts {
    width: 1%;
  }
  .tool {
    display: inline-grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border: 0;
    background: transparent;
    color: var(--ink-3);
    border-radius: 3px;
  }
  .tool:hover {
    background: var(--surface-3);
    color: var(--ink);
  }
  .add {
    display: flex;
    gap: 6px;
    margin-top: 4px;
  }
  .add .input {
    max-width: 220px;
  }
  .add1 {
    margin-top: 4px;
    height: 24px;
  }
</style>
