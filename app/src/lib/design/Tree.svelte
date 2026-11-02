<script lang="ts">
  import { app, select, findNode, parentOf, move, canMove, duplicate, copySelected, paste, removeSelected, clipboard, placement } from "../state.svelte";
  import { keys } from "../keys";
  import TreeNode from "./TreeNode.svelte";
  import { tree, toggleFold, visibleRows } from "./treeState.svelte";

  let root = $derived(app.design?.tree);
  let host: HTMLDivElement;

  function focusRow(id: string) {
    requestAnimationFrame(() => (host?.querySelector(`[data-id="${id}"]`) as HTMLElement | null)?.focus());
  }

  // Arrow keys walk the list as in a file browser: up and down, right unfolds (or goes in), left folds (or goes out).
  function key(e: KeyboardEvent) {
    if (e.altKey || e.ctrlKey || e.metaKey || !root) return;
    const rows = visibleRows(root);
    const i = rows.findIndex((n) => n.id === app.selected);
    const cur = rows[i];
    let to: string | null = null;
    if (e.key === "ArrowDown") to = rows[Math.min(rows.length - 1, i + 1)]?.id;
    else if (e.key === "ArrowUp") to = rows[Math.max(0, i - 1)]?.id;
    else if (e.key === "Home") to = rows[0].id;
    else if (e.key === "End") to = rows[rows.length - 1].id;
    else if (e.key === "ArrowRight" && cur?.children.length) {
      if (tree.folded.has(cur.id)) toggleFold(cur.id);
      else to = cur.children[0].id;
    } else if (e.key === "ArrowLeft" && cur) {
      if (cur.children.length && !tree.folded.has(cur.id)) toggleFold(cur.id);
      else to = parentOf(root, cur.id)?.id ?? null;
    } else if (e.key === "Enter" && cur) {
      // Straight to the part's first setting.
      (document.querySelector('[data-panel="design.props"] .sections input, [data-panel="design.props"] .sections select') as HTMLElement | null)?.focus();
    } else if (e.key === "F2" && cur && cur.id !== "rocket") {
      rename();
    } else return;
    e.preventDefault();
    if (to && to !== app.selected) {
      select(to);
      focusRow(to);
    }
  }

  function rename() {
    const el = document.querySelector('[data-panel="design.props"] .head .name') as HTMLInputElement | null;
    el?.focus();
    el?.select();
  }

  let menuFor = $derived(tree.menu ? findNode(root, tree.menu.id) : null);
  let pasteInto = $derived(clipboard.id && tree.menu ? placement(clipboard.type) : null);
  function item(f: () => void) {
    return () => {
      tree.menu = null;
      f();
    };
  }
  function closeMenu(e: Event) {
    if (tree.menu && !(e.target as HTMLElement).closest?.(".menu")) tree.menu = null;
  }
</script>

<svelte:window onpointerdown={closeMenu} onkeydown={(e) => e.key === "Escape" && (tree.menu = null)} onblur={() => (tree.menu = null)} />

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div class="tree" role="tree" tabindex="-1" aria-label="Parts" bind:this={host} onkeydown={key}>
  {#if root}
    <TreeNode node={root} depth={0} />
  {/if}
</div>
<div class="hint">Drag a part to move it, into a part or between two. Right-click for more.</div>

{#if tree.menu && menuFor}
  {@const isRoot = menuFor.id === "rocket"}
  <div class="menu" role="menu" style="left: {Math.min(tree.menu.x, innerWidth - 220)}px; top: {Math.min(tree.menu.y, innerHeight - 280)}px">
    <div class="who">{menuFor.name}</div>
    <button role="menuitem" disabled={isRoot} onclick={item(rename)}>Rename<span>F2</span></button>
    <button role="menuitem" disabled={isRoot} onclick={item(duplicate)}>Duplicate<span>{keys("D")}</span></button>
    <button role="menuitem" disabled={isRoot} onclick={item(copySelected)}>Copy<span>{keys("C")}</span></button>
    <button role="menuitem" disabled={!pasteInto} onclick={item(paste)} title={clipboard.id ? "" : "Copy a part first"}>
      Paste{clipboard.id ? " " + clipboard.name : ""}<span>{keys("V")}</span>
    </button>
    <hr />
    <button role="menuitem" disabled={!canMove(-1)} onclick={item(() => move(-1))}>Move up<span>Alt+↑</span></button>
    <button role="menuitem" disabled={!canMove(1)} onclick={item(() => move(1))}>Move down<span>Alt+↓</span></button>
    <hr />
    <button role="menuitem" class="danger" disabled={isRoot} onclick={item(removeSelected)}>Delete<span>Del</span></button>
  </div>
{/if}

<style>
  .tree {
    flex: 1;
    overflow: auto;
    padding: 3px 0;
  }
  .hint {
    flex: none;
    padding: 4px 8px;
    border-top: 1px solid var(--line);
    font-size: 11.5px;
    color: var(--ink-3);
  }
  .menu {
    position: fixed;
    z-index: 60;
    min-width: 200px;
    padding: 4px;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: 8px;
    box-shadow: var(--shadow-pop);
  }
  .who {
    padding: 3px 8px 5px;
    font-size: 11.5px;
    color: var(--ink-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 260px;
  }
  .menu button {
    display: flex;
    width: 100%;
    justify-content: space-between;
    gap: 16px;
    border: 0;
    background: transparent;
    padding: 5px 8px;
    border-radius: 5px;
    text-align: left;
    color: var(--ink);
  }
  .menu button span {
    color: var(--ink-3);
    font-size: 12px;
  }
  .menu button:hover:not(:disabled) {
    background: var(--select-soft);
  }
  .menu button:disabled {
    color: var(--ink-3);
    opacity: 0.6;
  }
  .menu .danger:not(:disabled) {
    color: var(--bad, #c62828);
  }
  hr {
    border: 0;
    border-top: 1px solid var(--line);
    margin: 4px 0;
  }
</style>
