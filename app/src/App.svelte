<script lang="ts">
  import { onMount } from "svelte";
  import { connected, keepAlive, launchFiles } from "./lib/api";
  import { app, init, run, undo, redo, save, openDialog, openPath, removeSelected, type Page } from "./lib/state.svelte";
  import { oneWindow, rememberPage, lastPage } from "./lib/oneWindow";
  import TopBar from "./lib/TopBar.svelte";
  import NavRail from "./lib/NavRail.svelte";
  import Home from "./lib/Home.svelte";
  import type { Component } from "svelte";
  import Toasts from "./lib/Toasts.svelte";
  import RocketLoader from "./lib/RocketLoader.svelte";
  import AskDialog from "./lib/AskDialog.svelte";
  import { issues, watchPageErrors } from "./lib/issues/issues.svelte";
  import { assistant, loadSettings } from "./lib/assistant/assistant.svelte";
  import { ui } from "./lib/search/ui.svelte";

  let ready = $state(false);

  // Each page's code (three.js for the design view, the plots, the analyses) loads when it is first shown, so the
  // start screen comes up on a small script; the rest is fetched in the background once the app has started.
  type Lazy = () => Promise<{ default: Component }>;
  const PAGE_CODE: Record<Exclude<Page, "home">, Lazy> = {
    design: () => import("./lib/design/DesignPage.svelte"),
    motors: () => import("./lib/motors/MotorsPage.svelte"),
    flights: () => import("./lib/flights/FlightsPage.svelte"),
    flightdata: () => import("./lib/flightdata/FlightDataPage.svelte"),
    standards: () => import("./lib/standards/StandardsPage.svelte"),
    targets: () => import("./lib/targets/TargetsPage.svelte"),
    analysis: () => import("./lib/analysis/AnalysisPage.svelte"),
  };
  const issuesCode: Lazy = () => import("./lib/issues/IssuesDrawer.svelte");
  const assistantCode: Lazy = () => import("./lib/assistant/AssistantDrawer.svelte");
  const searchCode: Lazy = () => import("./lib/search/Search.svelte");
  const loaded = new Map<Lazy, Promise<{ default: Component }>>();
  const load = (f: Lazy) => {
    if (!loaded.has(f)) loaded.set(f, f());
    return loaded.get(f)!;
  };
  function prefetch() {
    for (const f of [PAGE_CODE.design, ...Object.values(PAGE_CODE), issuesCode, assistantCode, searchCode]) load(f).catch(() => {});
  }
  let replaced = $state(false);
  const PAGES: Page[] = ["home", "design", "motors", "flights", "flightdata", "targets", "standards", "analysis"];

  onMount(() => {
    if (!connected) return;
    keepAlive();
    watchPageErrors();
    oneWindow(() => (replaced = true));
    run(init)
      .then(async () => {
        // Designs double-clicked in Finder or Explorer, or handed over by a second launch; else where the last window was.
        for (const f of launchFiles) await openPath(f);
        const last = lastPage() as Page | null;
        if (launchFiles.length && app.design) app.page = "design";
        else if (last && PAGES.includes(last) && app.design) app.page = last;
      })
      .finally(() => {
        ready = true;
        setTimeout(prefetch, 300);
      });
    loadSettings();
  });

  $effect(() => {
    if (ready) rememberPage(app.page);
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
    } else if (mod && e.key.toLowerCase() === "k") {
      e.preventDefault();
      ui.search = !ui.search;
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
          <div class="loading"><RocketLoader text="Starting OpenRocket and loading its motors and parts" /></div>
        {:else if app.page === "home" || !app.design}
          <Home />
        {:else}
          {#key app.page}
            {#await load(PAGE_CODE[app.page])}
              <div class="loading"><RocketLoader size="small" /></div>
            {:then m}
              <m.default />
            {/await}
          {/key}
        {/if}
      </main>
      {#if issues.open}{#await load(issuesCode) then m}<m.default />{/await}{/if}
      {#if assistant.open && assistant.settings?.enabled}{#await load(assistantCode) then m}<m.default />{/await}{/if}
    </div>
  </div>
  <Toasts />
  <AskDialog />
  {#if ui.search}{#await load(searchCode) then m}<m.default />{/await}{/if}
  {#if replaced}
    <div class="replaced" role="alert">
      <div>
        <b>RocketMaxxing continues in the newer window.</b>
        <p>Everything open here is open there too. You can close this one.</p>
      </div>
    </div>
  {/if}
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
  .replaced {
    position: fixed;
    inset: 0;
    z-index: 100;
    display: grid;
    place-items: center;
    background: color-mix(in srgb, var(--surface) 85%, transparent);
    backdrop-filter: blur(2px);
    text-align: center;
  }
  .replaced p {
    margin: 6px 0 0;
    color: var(--ink-2);
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
