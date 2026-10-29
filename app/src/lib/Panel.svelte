<script lang="ts">
  import type { Snippet } from "svelte";
  import { ChevronDown, PanelLeftClose, PanelRightClose } from "@lucide/svelte";
  import { isOpen, toggle } from "./panels.svelte";

  // A titled panel that folds away. Side panels fold to a narrow strip so the view gets the width.
  let {
    id,
    title,
    side,
    initial = true,
    fill = false,
    tools,
    status,
    children,
  }: {
    id: string;
    title: string;
    side?: "left" | "right";
    initial?: boolean;
    fill?: boolean;
    tools?: Snippet;
    status?: Snippet;
    children: Snippet;
  } = $props();

  let open = $derived(isOpen(id, initial));
</script>

{#if side && !open}
  <button class="strip {side}" onclick={() => toggle(id, initial)} title="Show {title.toLowerCase()}">
    <span>{title}</span>
  </button>
{:else}
  <section class="panel" class:fill={fill && open} class:side>
    <header>
      {#if side}
        <span class="title">{title}</span>
      {:else}
        <button class="title fold" onclick={() => toggle(id, initial)} aria-expanded={open}>
          <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
          {title}
        </button>
      {/if}
      <div class="tools">
        {#if status}{@render status()}{/if}
        {#if open && tools}{@render tools()}{/if}
        {#if side}
          <button class="hide" onclick={() => toggle(id, initial)} title="Hide {title.toLowerCase()}">
            {#if side === "left"}<PanelLeftClose size={15} />{:else}<PanelRightClose size={15} />{/if}
          </button>
        {/if}
      </div>
    </header>
    {#if open}
      <div class="body">{@render children()}</div>
    {/if}
  </section>
{/if}

<style>
  .panel {
    display: flex;
    flex-direction: column;
    min-height: 0;
    min-width: 0;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .panel.fill {
    flex: 1;
  }
  .panel.side {
    height: 100%;
  }
  header {
    flex: none;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    height: 32px;
    padding: 0 6px 0 8px;
    background: var(--surface-2);
    border-bottom: 1px solid var(--line);
  }
  .title {
    display: flex;
    align-items: center;
    gap: 4px;
    font-weight: 600;
    font-size: 12.5px;
    color: var(--ink-2);
    white-space: nowrap;
  }
  .fold {
    border: 0;
    background: transparent;
    padding: 0;
    height: 100%;
  }
  .fold:hover {
    color: var(--ink);
  }
  .chev {
    display: flex;
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .tools {
    display: flex;
    align-items: center;
    gap: 2px;
    min-width: 0;
  }
  .hide {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border: 0;
    border-radius: 3px;
    background: transparent;
    color: var(--ink-3);
  }
  .hide:hover {
    background: var(--surface-3);
    color: var(--ink);
  }
  .body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: auto;
  }
  .strip {
    flex: none;
    width: 28px;
    height: 100%;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-2);
    color: var(--ink-2);
    font-weight: 600;
    font-size: 12.5px;
    padding: 10px 0;
    display: flex;
    justify-content: center;
  }
  .strip:hover {
    background: var(--surface-3);
    color: var(--ink);
  }
  .strip span {
    writing-mode: vertical-rl;
  }
  .strip.left span {
    transform: rotate(180deg);
  }
</style>
