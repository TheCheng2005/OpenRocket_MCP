<script lang="ts">
  import { onMount } from "svelte";
  import { connected, keepAlive } from "./lib/api";
  import { app, init, run, undo, redo, save, openDialog, removeSelected } from "./lib/state.svelte";
  import TopBar from "./lib/TopBar.svelte";
  import NavRail from "./lib/NavRail.svelte";
  import Home from "./lib/Home.svelte";
  import DesignPage from "./lib/design/DesignPage.svelte";
  import MotorsPage from "./lib/motors/MotorsPage.svelte";
  import FlightsPage from "./lib/flights/FlightsPage.svelte";
  import AnalysisPage from "./lib/analysis/AnalysisPage.svelte";
  import TargetsPage from "./lib/targets/TargetsPage.svelte";
  import Toasts from "./lib/Toasts.svelte";
  import AskDialog from "./lib/AskDialog.svelte";

  let ready = $state(false);

  onMount(() => {
    if (!connected) return;
    keepAlive();
    run(init).finally(() => (ready = true));
  });

  function typing(e: KeyboardEvent) {
    const t = e.target as HTMLElement;
    return t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable;
  }

  function onKey(e: KeyboardEvent) {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === "z" && !typing(e)) {
      e.preventDefault();
      e.shiftKey ? redo() : undo();
    } else if (mod && e.key.toLowerCase() === "y" && !typing(e)) {
      e.preventDefault();
      redo();
    } else if (mod && e.key.toLowerCase() === "s") {
      e.preventDefault();
      save(e.shiftKey);
    } else if (mod && e.key.toLowerCase() === "o") {
      e.preventDefault();
      openDialog();
    } else if ((e.key === "Delete" || e.key === "Backspace") && !typing(e) && app.page === "design") {
      e.preventDefault();
      removeSelected();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

{#if !connected}
  <div class="offline">
    <h1>RocketMaxxing</h1>
    <p>Start RocketMaxxing with <code>rocketmaxxing</code>. It opens this window itself.</p>
  </div>
{:else}
  <div class="shell">
    <TopBar />
    <div class="body">
      <NavRail />
      <main>
        {#if !ready}
          <div class="loading">Loading…</div>
        {:else if app.page === "home" || !app.design}
          <Home />
        {:else if app.page === "design"}
          <DesignPage />
        {:else if app.page === "motors"}
          <MotorsPage />
        {:else if app.page === "flights"}
          <FlightsPage />
        {:else if app.page === "targets"}
          <TargetsPage />
        {:else if app.page === "analysis"}
          <AnalysisPage />
        {/if}
      </main>
    </div>
  </div>
  <Toasts />
  <AskDialog />
{/if}

<style>
  .shell {
    display: flex;
    flex-direction: column;
    height: 100%;
  }
  .body {
    flex: 1;
    display: flex;
    min-height: 0;
  }
  main {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .loading {
    margin: auto;
    color: var(--ink-3);
    font-size: 16px;
  }
  .offline {
    max-width: 520px;
    margin: 15vh auto;
    padding: 0 16px;
    text-align: center;
  }
  .offline code {
    background: var(--surface-3);
    padding: 2px 6px;
    border-radius: 5px;
  }
</style>
