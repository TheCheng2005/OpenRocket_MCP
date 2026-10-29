<script lang="ts">
  import { app, autoCheck } from "../state.svelte";
  import { status } from "../analysis/text";

  // The competition rule check, re-run after edits: failures and warnings first, passes on request.
  let showPassed = $state(false);

  let c = $derived(app.checks && app.checks.designId === app.design?.designId ? app.checks : null);
  type Level = "fail" | "warn" | "info" | "pass";
  let rows = $derived((c?.checks ?? []).map((x) => ({ ...x, s: (status(x.status) || "info") as Level })));
  const order: Record<Level, number> = { fail: 0, warn: 1, info: 2, pass: 3 };
  let shown = $derived(rows.filter((r) => showPassed || r.s !== "pass").sort((a, b) => order[a.s] - order[b.s]));
  let passed = $derived(rows.filter((r) => r.s === "pass").length);
</script>

<div class="rc" class:stale={app.checking}>
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
