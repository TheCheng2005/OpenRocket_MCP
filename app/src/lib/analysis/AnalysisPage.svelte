<script lang="ts">
  import { untrack } from "svelte";
  import { Play, ChevronDown } from "@lucide/svelte";
  import { call, callFull, tools as listTools, type ToolInfo } from "../api";
  import { app, refresh, run, type TreeNode } from "../state.svelte";
  import { isOpen, toggle } from "../panels.svelte";
  import Panel from "../Panel.svelte";
  import Result from "./Result.svelte";
  import ResultPlot from "./ResultPlot.svelte";
  import RowsEditor from "./RowsEditor.svelte";
  import { CATALOG, LONG_INPUTS, OVERRIDES, PART_INPUTS, type Analysis } from "./catalog";
  import { analysis } from "./store.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { firstSentence, label } from "./text";

  // The engine's analyses: pick one, fill in what it needs (the design is filled in), run, read the result.

  let infos = $state<Record<string, ToolInfo>>({});
  let sims = $state<string[]>([]);
  let designs = $state<{ designId: string; name: string }[]>([]);

  listTools().then((list) => (infos = Object.fromEntries(list.map((t) => [t.name, t]))));

  $effect(() => {
    void app.revision;
    untrack(async () => {
      if (!app.design) return;
      const r = await call<{ simulations: { name: string }[] }>("app_sims", { designId: app.design.designId }).catch(() => null);
      sims = r?.simulations.map((s) => s.name) ?? [];
      const ses = await call<{ designs: { designId: string; name: string }[] }>("app_session").catch(() => null);
      designs = ses?.designs.filter((x) => x.designId !== app.design?.designId) ?? [];
    });
  });

  let entry = $derived(CATALOG.flatMap((g) => g.items).find((a) => a.tool === analysis.selected) ?? CATALOG[0].items[0]);
  let info = $derived(infos[entry.tool]);
  let outcome = $derived(analysis.results[entry.tool]);
  let stale = $derived(!!outcome && (outcome.designId !== app.design?.designId || outcome.revision < app.revision));
  let running = $state(false);

  type Kind = "sim" | "config" | "part" | "design" | "file" | "rows" | "enum" | "qty" | "bool" | "number" | "list" | "text" | "long";

  function kind(key: string, s: any): Kind | null {
    if (key === "designId") return null;
    if (key === "simulation") return "sim";
    if (key === "configuration") return "config";
    if (key === "baselineDesignId") return "design";
    if (entry.files?.[key]) return "file";
    if (PART_INPUTS[key]) return "part";
    if (s.enum) return "enum";
    if (Array.isArray(s.type)) return "qty";
    if (s.type === "boolean") return "bool";
    if (s.type === "number" || s.type === "integer") return "number";
    if (s.type === "array") {
      if (s.items?.type === "object") return s.items.properties ? "rows" : null;
      return s.items ? "list" : null;
    }
    if (s.type === "string") return LONG_INPUTS.includes(key) ? "long" : "text";
    return null; // objects and lists of objects need their own forms (tier 2)
  }

  interface Input {
    key: string;
    kind: Kind;
    schema: any;
    required: boolean;
  }

  let inputs = $derived.by(() => {
    const props = info?.inputSchema.properties ?? {};
    const req = new Set(info?.inputSchema.required ?? []);
    const all: Input[] = [];
    for (const [key, schema] of Object.entries(props)) {
      const k = kind(key, schema);
      if (k) all.push({ key, kind: k, schema, required: req.has(key) });
    }
    const main = entry.main.map((k) => all.find((i) => i.key === k)).filter((i): i is Input => !!i);
    for (const i of all) if (i.required && !main.includes(i)) main.unshift(i);
    const conditions = all.filter((i) => OVERRIDES.includes(i.key) && !main.includes(i));
    const more = all.filter((i) => !main.includes(i) && !conditions.includes(i));
    return { main, conditions, more };
  });

  function fileName() {
    return (app.design?.name ?? "design").replace(/[^A-Za-z0-9._-]+/g, "_");
  }

  /** Each analysis keeps its own inputs, starting from its defaults. */
  function ensure(a: Analysis) {
    analysis.values[a.tool] ??= { ...(a.defaults?.(fileName()) ?? {}) };
  }
  untrack(() => ensure(entry)); // the analysis shown first
  let values = $derived(analysis.values[entry.tool]);

  // A required part input starts on the first part that fits (the only parachute, the motor mount).
  $effect(() => {
    for (const i of inputs.main) {
      if (i.kind === "part" && i.required && !untrack(() => values[i.key])) {
        const first = partsFor(i.key)[0];
        if (first) untrack(() => (values[i.key] = first.id));
      }
    }
  });

  /** The filled-in rows of a list input. */
  function filledRows(raw: string): Record<string, string>[] {
    try {
      const r = JSON.parse(raw || "[]");
      return Array.isArray(r) ? r.filter((row) => Object.values(row ?? {}).some((v) => String(v ?? "").trim() !== "")) : [];
    } catch {
      return [];
    }
  }

  let missing = $derived(
    inputs.main
      .filter((i) => i.required && (i.kind === "rows" ? filledRows(values[i.key] ?? "").length === 0 : !(values[i.key] ?? "").trim()))
      .map((i) => label(i.key)),
  );

  function flatten(n: TreeNode | undefined, out: TreeNode[] = []): TreeNode[] {
    if (!n) return out;
    if (n.id !== "rocket") out.push(n);
    for (const c of n.children) flatten(c, out);
    return out;
  }
  let parts = $derived(flatten(app.design?.tree));

  function partsFor(key: string) {
    const want = PART_INPUTS[key];
    if (want === "any") return parts;
    if (want === "mount") return parts.filter((p) => p.motorMount);
    return parts.filter((p) => (want as string[]).includes(p.type));
  }

  const NUMBER = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;

  function args(): Record<string, unknown> {
    const out: Record<string, unknown> = { designId: app.design?.designId };
    for (const i of [...inputs.main, ...inputs.conditions, ...inputs.more]) {
      const raw = (values[i.key] ?? "").trim();
      if (raw === "") continue;
      if (i.kind === "rows") {
        const props = i.schema.items?.properties ?? {};
        const list = filledRows(raw).map((row) => {
          const o: Record<string, unknown> = {};
          for (const [k, v] of Object.entries(row)) {
            const t = String(v ?? "").trim();
            if (!t) continue;
            const ty = props[k]?.type;
            if (ty === "boolean") o[k] = t === "true";
            else if ((ty === "number" || ty === "integer" || Array.isArray(ty)) && NUMBER.test(t)) o[k] = Number(t);
            else o[k] = t;
          }
          return o;
        });
        if (list.length) out[i.key] = list;
        continue;
      }
      if (i.kind === "bool") out[i.key] = raw === "true";
      else if (i.kind === "number") out[i.key] = NUMBER.test(raw) ? Number(raw) : raw;
      else if (i.kind === "qty") out[i.key] = NUMBER.test(raw) ? Number(raw) : raw;
      else if (i.kind === "list") {
        const numeric = i.schema.items?.type === "number" || i.schema.items?.type === "integer";
        out[i.key] = raw.split(/[,;]+/).map((x) => x.trim()).filter(Boolean)
          .map((x) => (numeric || Array.isArray(i.schema.items?.type)) && NUMBER.test(x) ? Number(x) : x);
      } else out[i.key] = raw;
    }
    if (!info?.inputSchema.properties?.designId) delete out.designId;
    return out;
  }

  async function go() {
    if (missing.length) return;
    const tool = entry.tool;
    const a = args();
    running = true;
    const t0 = performance.now();
    const r = await run(() => callFull(tool, a));
    running = false;
    if (!r) return;
    if (!info?.annotations?.readOnlyHint) await refresh(); // it may have changed the design (apply)
    analysis.results[tool] = { ...r, ms: performance.now() - t0, revision: app.revision, designId: app.design?.designId ?? "" };
  }

  async function browse(key: string) {
    try {
      const r = await call<{ path: string | null }>("app_choose_file", { mode: "open", types: entry.files?.[key] ?? "csv" });
      if (r.path) values[key] = r.path;
    } catch {
      /* no file dialog here: the path can be typed */
    }
  }

  function choose(a: Analysis) {
    ensure(a);
    analysis.selected = a.tool;
  }
</script>

{#snippet field(i: Input)}
  {@const help = i.schema.description ?? ""}
  {#if i.kind === "rows"}
    <div class="rowsfield">
      <span class="lab" title={help}>{label(i.key)}{#if i.required}<b class="req">*</b>{/if}</span>
      <RowsEditor items={i.schema.items} bind:value={values[i.key]} {parts} />
    </div>
  {:else if i.kind === "long"}
    <label class="rowsfield" title={help}>
      <span class="lab">{label(i.key)}{#if i.required}<b class="req">*</b>{/if}</span>
      <textarea class="long" rows="4" bind:value={values[i.key]} placeholder={firstSentence(help)}></textarea>
    </label>
  {:else}
  <label class="row" title={help}>
    <span class="lab">{label(i.key)}{#if i.required}<b class="req">*</b>{/if}</span>
    {#if i.kind === "sim"}
      <select class="select" bind:value={values[i.key]}>
        <option value="">Active configuration</option>
        {#each sims as s}<option value={s}>{s}</option>{/each}
      </select>
    {:else if i.kind === "config"}
      <select class="select" bind:value={values[i.key]}>
        <option value="">Active</option>
        {#each app.design?.configurations ?? [] as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
      </select>
    {:else if i.kind === "part"}
      <select class="select" bind:value={values[i.key]}>
        <option value="">{i.required ? "Choose…" : "Default"}</option>
        {#each partsFor(i.key) as p (p.id)}<option value={p.id}>{p.name}</option>{/each}
      </select>
    {:else if i.kind === "design"}
      <select class="select" bind:value={values[i.key]}>
        <option value="">None</option>
        {#each designs as d (d.designId)}<option value={d.designId}>{d.name}</option>{/each}
      </select>
    {:else if i.kind === "file"}
      <span class="file">
        <input class="input" bind:value={values[i.key]} placeholder="path of the file" />
        <button class="btn" type="button" onclick={() => browse(i.key)}>Browse…</button>
      </span>
    {:else if i.kind === "enum"}
      <select class="select" bind:value={values[i.key]}>
        {#if !i.required}<option value="">Default</option>{/if}
        {#each i.schema.enum as e}<option value={e}>{e}</option>{/each}
      </select>
    {:else if i.kind === "bool"}
      <select class="select" bind:value={values[i.key]}>
        <option value="">Default</option><option value="true">Yes</option><option value="false">No</option>
      </select>
    {:else}
      <input class="input" bind:value={values[i.key]}
        placeholder={i.kind === "qty" ? "with unit, e.g. 3 in" : i.kind === "list" ? "comma separated" : i.key === "pinType" ? "e.g. 4-40 nylon" : ""}
        onkeydown={(e) => e.key === "Enter" && go()} />
    {/if}
  </label>
  {/if}
{/snippet}

{#snippet fold(id: string, title: string, list: Input[])}
  {#if list.length}
    <section class="fold">
      <button class="fh" onclick={() => toggle(id, false)} aria-expanded={isOpen(id, false)}>
        <span class="chev" class:shut={!isOpen(id, false)}><ChevronDown size={14} /></span>{title}
      </button>
      {#if isOpen(id, false)}
        <div class="fields">{#each list as i (i.key)}{@render field(i)}{/each}</div>
      {/if}
    </section>
  {/if}
{/snippet}

<div class="page">
  <div class="left" class:shut={!isOpen("analysis.list")}>
    <Panel id="analysis.list" title="Analyses" side="left">
      <nav class="cat">
        {#each CATALOG as g (g.title)}
          {@const gid = "analysis.group." + g.title}
          <button class="gh" onclick={() => toggle(gid)} aria-expanded={isOpen(gid)}>
            <span class="chev" class:shut={!isOpen(gid)}><ChevronDown size={14} /></span>{g.title}
          </button>
          {#if isOpen(gid)}
            {#each g.items as a (a.tool)}
              <button class="item" class:on={a.tool === entry.tool} onclick={() => choose(a)}>
                {a.label}
                {#if analysis.results[a.tool]}<span class="ran" title="Has a result"></span>{/if}
              </button>
            {/each}
          {/if}
        {/each}
      </nav>
    </Panel>
  </div>

  <div class="main">
    <Panel id="analysis.inputs" title={entry.label}>
      {#snippet tools()}
        {#if missing.length}<span class="meta">Needs: {missing.join(", ")}</span>{/if}
        <button class="btn primary run" disabled={running || !info || missing.length > 0} onclick={go}><Play size={14} /> {running ? "Running…" : "Run"}</button>
      {/snippet}
      <div class="form">
        {#if info}
          <p class="desc" title={info.description}>{entry.blurb ?? firstSentence(info.description)}</p>
          <div class="fields">{#each inputs.main as i (i.key)}{@render field(i)}{/each}</div>
          {@render fold("analysis.more", "More options", inputs.more)}
          {@render fold("analysis.conditions", "Launch conditions for this run", inputs.conditions)}
        {:else}
          <p class="muted">Loading…</p>
        {/if}
      </div>
    </Panel>

    <Panel id="analysis.results" title="Result" fill>
      {#snippet tools()}
        {#if outcome}<span class="meta">{stale ? "Design changed since this ran. " : ""}{(outcome.ms / 1000).toFixed(1)} s</span>{/if}
      {/snippet}
      <div class="out" class:stale>
        {#if running}<RocketLoader text={entry.label} />{/if}
        {#if outcome}
          {#if entry.plot}<ResultPlot data={outcome.data} spec={entry.plot} />{/if}
          {#each outcome.images as img}<img src={img.src} alt="Plot" />{/each}
          <Result value={outcome.data} />
        {:else}
          {#if !running}<p class="muted">Not run yet.</p>{/if}
        {/if}
      </div>
    </Panel>
  </div>
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    display: flex;
    gap: 6px;
    padding: 6px;
  }
  .left {
    width: 230px;
    flex: none;
    min-height: 0;
  }
  .left.shut {
    width: auto;
  }
  .main {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .cat {
    display: flex;
    flex-direction: column;
    padding: 3px 0;
  }
  .gh {
    display: flex;
    align-items: center;
    gap: 4px;
    border: 0;
    background: transparent;
    padding: 6px 8px 3px;
    font-weight: 600;
    text-align: left;
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    border: 0;
    background: transparent;
    padding: 4px 8px 4px 26px;
    text-align: left;
    color: var(--ink-2);
  }
  .item:hover {
    background: var(--surface-2);
    color: var(--ink);
  }
  .item.on {
    background: var(--select-soft);
    color: var(--ink);
  }
  .ran {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--ink-3);
  }
  .run {
    height: 24px;
  }
  .form {
    padding: 6px 10px 10px;
    max-height: 46vh;
    overflow: auto;
  }
  .desc {
    margin: 0 0 8px;
    color: var(--ink-2);
  }
  .fields {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
    gap: 4px 18px;
    align-items: start;
  }
  .row {
    display: grid;
    grid-template-columns: 44% 1fr;
    align-items: center;
    gap: 6px;
    min-height: 28px;
  }
  .lab {
    color: var(--ink-2);
  }
  .req {
    color: var(--bad);
    margin-left: 2px;
  }
  .row .input,
  .row .select {
    width: 100%;
  }
  .rowsfield {
    grid-column: 1 / -1;
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 2px 0 4px;
  }
  .long {
    resize: vertical;
    font: inherit;
    font-size: 12px;
    padding: 4px 6px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--ink);
  }
  .long:focus {
    outline: none;
    border-color: var(--select);
  }
  .file {
    display: flex;
    gap: 4px;
    min-width: 0;
  }
  .file .input {
    flex: 1;
    min-width: 0;
  }
  .fold {
    margin-top: 6px;
    border-top: 1px solid var(--line);
  }
  .fh {
    display: flex;
    align-items: center;
    gap: 4px;
    border: 0;
    background: transparent;
    padding: 6px 0;
    font-weight: 600;
  }
  .meta {
    font-size: 12px;
    color: var(--ink-3);
    white-space: nowrap;
  }
  .out {
    padding: 6px 12px 12px;
  }
  .out.stale {
    opacity: 0.75;
  }
  img {
    max-width: 100%;
    display: block;
    margin: 4px 0 10px;
    border: 1px solid var(--line);
    background: #fff;
  }
</style>
