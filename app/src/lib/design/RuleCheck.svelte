<script lang="ts">
  import { app, autoCheck, setAutoCheck, checkRules, toast } from "../state.svelte";
  import { context, copyText, type MenuItem } from "../menu/menu.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { status } from "../analysis/text";

  let { tall = false }: { tall?: boolean } = $props();
  // The competition rule check, re-run after edits: failures and warnings first, passes on request.
  let showPassed = $state(false);

  let c = $derived(app.checks && app.checks.designId === app.design?.designId ? app.checks : null);
  type Level = "fail" | "warn" | "info" | "pass";
  let rows = $derived((c?.checks ?? []).map((x) => ({ ...x, s: (status(x.status) || "info") as Level })));
  const order: Record<Level, number> = { fail: 0, warn: 1, info: 2, pass: 3 };
  let shown = $derived(rows.filter((r) => showPassed || r.s !== "pass").sort((a, b) => order[a.s] - order[b.s]));
  let passed = $derived(rows.filter((r) => r.s === "pass").length);

  // Right-click: check now, copy the results, show or hide passes, change the rule set.
  function items(e: MouseEvent): MenuItem[] {
    const tr = (e.target as HTMLElement).closest("tr");
    const i = tr ? [...tr.parentElement!.children].indexOf(tr) : -1;
    const r = i >= 0 ? shown[i] : null;
    const line = (x: (typeof rows)[number]) => `${x.status}  ${x.item}${x.value ? ": " + x.value : ""}${x.requirement ? " (" + x.requirement + ")" : ""}${x.ref ? " [" + x.ref + "]" : ""}`;
    return [
      { header: "Rule check" },
      r ? { label: "Copy this line", run: () => copyText(line(r)).then((ok) => ok && toast("Copied")) } : null,
      { label: "Copy all results", off: !rows.length && "No results yet", run: () => copyText([c?.ruleSet ?? "", ...rows.map(line)].join("\n")).then((ok) => ok && toast("Copied the rule check")) },
      { label: "Check again", run: () => checkRules() },
      { label: showPassed ? "Hide passed" : "Show passed", run: () => (showPassed = !showPassed) },
      "-",
      { label: "Check after every edit", checked: autoCheck(), run: () => setAutoCheck(!autoCheck()) },
      { label: "Rule set and launch site (Standards)", run: () => (app.page = "standards") },
      { label: "Targets", run: () => (app.page = "targets") },
      { label: "Rule check in full (Analysis)", run: () => ((analysis.selected = "check_requirements"), (app.page = "analysis")) },
    ].filter(Boolean) as MenuItem[];
  }
</script>

<div class="rc" class:stale={app.checking} class:tall use:context={items}>
  {#if !c}
    <p class="muted pad">{autoCheck() ? "Waiting for the first check…" : "Click the refresh button to check the rules (automatic checking is off in Settings)."}</p>
  {:else if c.error}
    <p class="muted pad">{c.error}</p>
  {:else}
    <table class="grid">
      <tbody>
        {#each shown as r, i (i)}
          <tr class={r.s}>
            <td class="st {r.s}">{r.status}</td>
            <td>{r.item}{#if r.requirement}<div class="req">{r.requirement}</div>{/if}</td>
            <td class="val">{r.value ?? ""}</td>
            <td class="ref">{r.ref ?? ""}</td>
          </tr>
        {/each}
      </tbody>
    </table>
    <div class="foot">
      <span class="muted">{c.ruleSet}</span>
      <button class="link" onclick={() => (showPassed = !showPassed)}>{showPassed ? "Hide passed" : `Show passed (${passed})`}</button>
    </div>
  {/if}
</div>

<style>
  .rc {
    max-height: 24vh;
    overflow: auto;
  }
  .rc.tall {
    max-height: none;
  }
  .rc.stale {
    opacity: 0.7;
  }
  .pad {
    margin: 0;
    padding: 8px 10px;
  }
  table td {
    vertical-align: top;
  }
  .st {
    font-weight: 600;
    white-space: nowrap;
    width: 1%;
  }
  .st.pass {
    color: var(--good);
  }
  .st.warn {
    color: var(--warn);
  }
  .st.fail {
    color: var(--bad);
  }
  tr.fail td {
    background: var(--bad-soft);
  }
  .req {
    font-size: 12px;
    color: var(--ink-3);
  }
  .val {
    width: 30%;
  }
  .ref {
    width: 1%;
    white-space: nowrap;
    font-size: 12px;
    color: var(--ink-3);
  }
  .foot {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    padding: 5px 10px;
    font-size: 12px;
  }
  .link {
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0;
    white-space: nowrap;
  }
</style>
