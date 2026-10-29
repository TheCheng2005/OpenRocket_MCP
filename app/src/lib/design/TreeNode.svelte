<script lang="ts">
  import { ChevronRight, Flame, Scale } from "@lucide/svelte";
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
  style="padding-left: {4 + depth * 14}px"
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
  <ComponentIcon type={node.type} size={24} />
  <span class="name" title="{node.name} ({node.label})">{node.name}</span>
  {#if node.motorMount}<span class="badge mount" title="Motor mount"><Flame size={12} /></span>{/if}
  {#if node.overridden}<span class="badge ovr" title="Mass or CG overridden"><Scale size={12} /></span>{/if}
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
    gap: 5px;
    height: 28px;
    padding-right: 6px;
    cursor: default;
    user-select: none;
  }
  .row:hover {
    background: var(--surface-2);
  }
  .row.sel {
    background: var(--select-soft);
  }
  .tw {
    width: 16px;
    height: 16px;
    flex: none;
    display: grid;
    place-items: center;
    border: 0;
    padding: 0;
    background: transparent;
    color: var(--ink-3);
    transition: transform 0.1s;
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
  .badge {
    display: grid;
    place-items: center;
    width: 18px;
    height: 18px;
    flex: none;
  }
  .mount {
    color: var(--accent);
  }
  .ovr {
    color: var(--warn);
  }
</style>
