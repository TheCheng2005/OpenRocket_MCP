<script lang="ts">
  import { ArrowUp, ArrowDown, Copy, Trash2 } from "@lucide/svelte";
  import { app, move, duplicate, removeSelected } from "../state.svelte";
  import TreeNode from "./TreeNode.svelte";

  let root = $derived(app.design?.tree);
  let isRoot = $derived(app.selected === "rocket");
</script>

<div class="head">
  <h2>Parts</h2>
  <div class="tools">
    <button class="btn ghost" title="Move up" disabled={isRoot} onclick={() => move(-1)}><ArrowUp size={17} /></button>
    <button class="btn ghost" title="Move down" disabled={isRoot} onclick={() => move(1)}><ArrowDown size={17} /></button>
    <button class="btn ghost" title="Duplicate" disabled={isRoot} onclick={duplicate}><Copy size={17} /></button>
    <button class="btn ghost danger" title="Delete (Del)" disabled={isRoot} onclick={removeSelected}><Trash2 size={17} /></button>
  </div>
</div>
<div class="tree" role="tree" aria-label="Rocket parts">
  {#if root}
    <TreeNode node={root} depth={0} />
  {/if}
</div>

<style>
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 10px 8px 14px;
    border-bottom: 1px solid var(--line);
  }
  h2 {
    margin: 0;
    font-size: 13px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--ink-3);
  }
  .tools {
    display: flex;
  }
  .tools .btn {
    height: 30px;
    padding: 0 7px;
  }
  .danger:hover:not(:disabled) {
    color: var(--bad);
  }
  .tree {
    flex: 1;
    overflow: auto;
    padding: 6px;
  }
</style>
