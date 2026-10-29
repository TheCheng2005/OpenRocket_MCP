<script lang="ts">
  import { ArrowUp, ArrowDown, Copy, Trash2 } from "@lucide/svelte";
  import { app, move, duplicate, removeSelected, findNode } from "../state.svelte";
  import { isOpen } from "../panels.svelte";
  import Panel from "../Panel.svelte";
  import Tree from "./Tree.svelte";
  import Palette from "./Palette.svelte";
  import Inspector from "./Inspector.svelte";
  import RocketView from "./RocketView.svelte";
  import Stats from "./Stats.svelte";

  let isRoot = $derived(app.selected === "rocket");
  let target = $derived(findNode(app.design?.tree, app.selected)?.name ?? "");
</script>

<div class="design">
  <div class="left" class:shut={!isOpen("design.parts")}>
    <Panel id="design.parts" title="Parts" side="left">
      {#snippet tools()}
        <button class="tool" title="Move up" disabled={isRoot} onclick={() => move(-1)}><ArrowUp size={15} /></button>
        <button class="tool" title="Move down" disabled={isRoot} onclick={() => move(1)}><ArrowDown size={15} /></button>
        <button class="tool" title="Duplicate" disabled={isRoot} onclick={duplicate}><Copy size={15} /></button>
        <button class="tool" title="Delete (Del)" disabled={isRoot} onclick={removeSelected}><Trash2 size={15} /></button>
      {/snippet}
      <Tree />
    </Panel>
  </div>

  <div class="center">
    <Panel id="design.view" title="View" fill>
      <div class="view"><RocketView /></div>
    </Panel>
    <Panel id="design.summary" title="Summary">
      <Stats />
    </Panel>
    <Panel id="design.add" title="Add part">
      {#snippet tools()}<span class="to">to {target}</span>{/snippet}
      <Palette />
    </Panel>
  </div>

  <div class="right" class:shut={!isOpen("design.props")}>
    <Panel id="design.props" title="Properties" side="right">
      <Inspector />
    </Panel>
  </div>
</div>

<style>
  .design {
    flex: 1;
    min-height: 0;
    display: flex;
    gap: 6px;
    padding: 6px;
  }
  .left {
    width: 260px;
    flex: none;
    min-height: 0;
  }
  .right {
    width: 330px;
    flex: none;
    min-height: 0;
  }
  .left.shut,
  .right.shut {
    width: auto;
  }
  .center {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .view {
    position: relative;
    flex: 1;
    min-height: 200px;
  }
  .tool {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border: 0;
    border-radius: 3px;
    background: transparent;
    color: var(--ink-2);
  }
  .tool:hover:not(:disabled) {
    background: var(--surface-3);
    color: var(--ink);
  }
  .tool:disabled {
    opacity: 0.35;
  }
  .to {
    font-size: 12px;
    color: var(--ink-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 300px;
  }
</style>
