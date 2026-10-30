<script lang="ts">
  import { onMount } from "svelte";
  import { call } from "../api";
  import { app, run, checkRules, checkTargets } from "../state.svelte";
  import Panel from "../Panel.svelte";
  import StdNode from "./StdNode.svelte";
  import { at, patchFor, type Json } from "./std";

  // The team's standards: the competition rule set, the launch site, and the safety factors, ratings and material data
  // the checks use. Only what the team changes is written to its file (openrocket-mcp.json), so it stays short and
  // readable, and Claude reads the same file.
  interface Std {
    file: string;
    fileExists: boolean;
    team: Json;
    merged: Json;
    defaults: Json;
    ruleSets: { id: string; name: string }[];
    ruleSet: string;
  }

  let std = $state<Std | null>(null);

  async function load() {
    std = (await run(() => call<Std>("app_standards"))) ?? null;
  }
  onMount(load);

  async function set(path: string[], value: Json) {
    const ok = await run(() => call("app_set_standards", { patch: patchFor(path, value) }));
    await load();
    if (ok && app.design) {
      checkRules();
      checkTargets();
    }
  }

  const SECTIONS: [string, string][] = [
    ["launchSite", "Launch site"],
    ["recovery", "Recovery"],
    ["structures", "Structures and materials"],
    ["electronics", "Electronics"],
  ];
  let ruleset = $derived(std ? String(at(std.merged, ["ruleset"]) ?? "none") : "");
</script>

<div class="page">
  {#if std}
    <div class="col">
      <Panel id="std.competition" title="Competition">
        <div class="body">
          <div class="row">
            <span>Rule set</span>
            <select class="select" value={ruleset} onchange={(e) => set(["ruleset"], (e.target as HTMLSelectElement).value)}>
              {#each std.ruleSets as r (r.id)}<option value={r.id}>{r.name}</option>{/each}
              {#if !std.ruleSets.some((r) => r.id === ruleset)}<option value={ruleset}>{ruleset}</option>{/if}
            </select>
          </div>
          <div class="row">
            <span>Competition year</span>
            <input class="input" type="number" value={String(at(std.merged, ["competitionYear"]) ?? "")}
              onchange={(e) => set(["competitionYear"], e.currentTarget.value ? Number(e.currentTarget.value) : null)} />
          </div>
          <label class="row">
            <span>Staged category</span>
            <input type="checkbox" checked={!!at(std.merged, ["stagedCategory"])} onchange={(e) => set(["stagedCategory"], e.currentTarget.checked)} />
          </label>
          <p class="muted small">The rule check (Design and Targets pages) uses this rule set. A team can also point to its own rule file: put its path in the team file's "ruleset".</p>
          <p class="muted small">
            {std.fileExists ? "Team file:" : "Not changed yet; changes go to"} <code>{std.file}</code>. <span class="dot">●</span> marks the team's own values;
            the rest are the built-in defaults.
          </p>
        </div>
      </Panel>
      {#each SECTIONS.slice(0, 2) as [key, title] (key)}
        <Panel id="std.{key}" {title}>
          <div class="body"><StdNode path={[key]} std={std} {set} /></div>
        </Panel>
      {/each}
    </div>
    <div class="col">
      {#each SECTIONS.slice(2) as [key, title] (key)}
        <Panel id="std.{key}" {title} initial={key !== "electronics"}>
          <div class="body"><StdNode path={[key]} std={std} {set} /></div>
        </Panel>
      {/each}
    </div>
  {:else}
    <p class="muted pad">Reading the standards…</p>
  {/if}
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    overflow: auto;
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 6px;
    padding: 6px;
    align-items: start;
  }
  @media (max-width: 1100px) {
    .page {
      grid-template-columns: 1fr;
    }
  }
  .col {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }
  .body {
    padding: 8px 12px 12px;
  }
  .row {
    display: grid;
    grid-template-columns: minmax(160px, 40%) 1fr 70px;
    align-items: center;
    gap: 8px;
    min-height: 30px;
    color: var(--ink-2);
  }
  .row input[type="checkbox"] {
    justify-self: start;
    width: 16px;
    height: 16px;
  }
  .small {
    font-size: 12px;
    margin: 6px 0 0;
  }
  .dot {
    color: var(--select);
    font-size: 9px;
  }
  code {
    font-size: 11.5px;
    word-break: break-all;
  }
  .pad {
    padding: 12px;
  }
</style>
