<script lang="ts">
  import { ChevronRight, Flame, Weight } from "@lucide/svelte";
  import { app, select, type TreeNode as Node } from "../state.svelte";
  import ComponentIcon from "../ComponentIcon.svelte";
  import Self from "./TreeNode.svelte";

  let { node, depth }: { node: Node; depth: number } = $props();
  let open = $state(true);
  let row: HTMLDivElement | undefined = $state();
  let selected = $derived(app.selected === node.id);

  $effect(() => {
    if (selected) row?.scrollIntoView({ block: "nearest" });
  });
</script>

<div
  bind:this={row}
  class="row"
  class:sel={selected}
  style="padding-left: {6 + depth * 16}px"
  role="treeitem"
  aria-selected={selected}
  aria-expanded={node.children.length ? open : undefined}
  tabindex="0"
  onclick={() => select(node.id)}
  onkeydown={(e) => e.key === "Enter" && select(node.id)}
>
  {#if node.children.length}
    <button class="tw" class:open onclick={(e) => (e.stopPropagation(), (open = !open))} aria-label={open ? "Collapse" : "Expand"}>
      <ChevronRight size={14} />
    </button>
  {:else}
    <span class="tw"></span>
  {/if}
  <ComponentIcon type={node.type} size={26} />
  <span class="name" title="{node.name} ({node.label})">{node.name}</span>
  {#if node.motorMount}<span class="badge mount" title="Motor mount"><Flame size={13} /></span>{/if}
  {#if node.overridden}<span class="badge ovr" title="Mass or CG overridden"><Weight size={13} /></span>{/if}
</div>
{#if open}
  {#each node.children as c (c.id)}
    <Self node={c} depth={depth + 1} />
  {/each}
{/if}

<style>
  .row {
    display: flex;
    align-items: center;
    gap: 7px;
    height: 36px;
    padding-right: 8px;
    border-radius: 8px;
    cursor: pointer;
    user-select: none;
  }
  .row:hover {
    background: var(--surface-3);
  }
  .row.sel {
    background: var(--select-soft);
    box-shadow: inset 3px 0 0 var(--select);
  }
  .tw {
    width: 18px;
    height: 18px;
    flex: none;
    display: grid;
    place-items: center;
    border: 0;
    padding: 0;
    background: transparent;
    color: var(--ink-3);
    transition: transform 0.12s;
  }
  .tw.open {
    transform: rotate(90deg);
  }
  .name {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sel .name {
    font-weight: 600;
  }
  .badge {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border-radius: 6px;
    flex: none;
  }
  .mount {
    color: var(--accent);
    background: var(--accent-soft);
  }
  .ovr {
    color: var(--warn);
    background: var(--warn-soft);
  }
</style>
