<script lang="ts">
  import { X } from "@lucide/svelte";
  import { app, setSystem, autoCheck, setAutoCheck } from "./state.svelte";
  import { units, choices, choose, unitOf, pretty, type Dim } from "./units.svelte";
  import { prefs, setTheme, type Theme } from "./prefs.svelte";
  import { assistant, configure, checkConnections, MODEL_NAMES, CLAUDE_CODE_MODELS, type Provider } from "./assistant/assistant.svelte";

  let { close }: { close: () => void } = $props();
  let key = $state("");

  const WAYS: { id: Provider; title: string; how: string }[] = [
    { id: "claude-code", title: "Claude subscription (Claude Code)", how: "Uses Claude Code on this computer, signed in with your Claude Pro or Max plan." },
    { id: "codex", title: "ChatGPT subscription (Codex)", how: "Uses Codex on this computer, signed in with your ChatGPT plan." },
    { id: "api", title: "Anthropic API key", how: "Pay per use with your own API key." },
  ];

  $effect(() => {
    if (assistant.settings?.enabled && !assistant.connections && !assistant.checking) checkConnections();
  });

  const QUANTITIES: [Dim, string][] = [
    ["LENGTH", "Length (parts)"], ["DISTANCE", "Distance and altitude"], ["VELOCITY", "Speed"],
    ["ACCELERATION", "Acceleration"], ["MASS", "Mass"], ["FORCE", "Force and thrust"], ["IMPULSE", "Impulse"],
    ["TIME", "Time"], ["ANGLE", "Angle"], ["AREA", "Area"], ["VOLUME", "Volume"], ["PRESSURE", "Pressure"],
    ["DENSITY", "Density"], ["TEMPERATURE", "Temperature"],
  ];
  const THEMES: [Theme, string][] = [["system", "Same as the system"], ["light", "Light"], ["dark", "Dark"]];
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && close()} />

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="dlg" role="dialog" aria-label="Settings">
    <header>
      <h3>Settings</h3>
      <button class="btn ghost" onclick={close} aria-label="Close"><X size={16} /></button>
    </header>

    <div class="content">
      <h4>Units</h4>
      <div class="row">
        <span>Default units</span>
        <div class="seg">
          <button class:on={units.system === "metric"} onclick={() => setSystem("metric")}>Metric</button>
          <button class:on={units.system === "imperial"} onclick={() => setSystem("imperial")}>Imperial</button>
        </div>
      </div>
      <p class="note">Choosing metric or imperial resets the units below.</p>
      <div class="units">
        {#each QUANTITIES as [dim, label] (dim)}
          <label class="row">
            <span>{label}</span>
            <select class="select" value={unitOf(dim).unit} onchange={(e) => choose(dim, (e.target as HTMLSelectElement).value)}>
              {#each choices(dim) as u (u.unit)}<option value={u.unit}>{pretty(u.unit)}</option>{/each}
            </select>
          </label>
        {/each}
      </div>

      <h4>Appearance</h4>
      <div class="row">
        <span>Theme</span>
        <select class="select" value={prefs.theme} onchange={(e) => setTheme((e.target as HTMLSelectElement).value as Theme)}>
          {#each THEMES as [t, label] (t)}<option value={t}>{label}</option>{/each}
        </select>
      </div>

      <h4>Checks</h4>
      <label class="row check">
        <span>Check the rules after each edit</span>
        <input type="checkbox" checked={autoCheck()} onchange={(e) => setAutoCheck((e.target as HTMLInputElement).checked)} />
      </label>

      <h4>AI assistant</h4>
      {#if assistant.settings}
        {@const a = assistant.settings}
        <label class="row check">
          <span>Use an AI assistant beside the design</span>
          <input type="checkbox" checked={a.enabled} onchange={(e) => configure({ enabled: (e.target as HTMLInputElement).checked })} />
        </label>
        {#if a.enabled}
          <div class="ways" role="radiogroup" aria-label="Connect with">
            {#each WAYS as w (w.id)}
              {@const c = assistant.connections?.find((x) => x.id === w.id)}
              <label class="way" class:on={a.provider === w.id}>
                <input type="radio" name="provider" checked={a.provider === w.id} onchange={() => configure({ provider: w.id })} />
                <div>
                  <b>{w.title}</b>
                  <div class="note">{w.how}</div>
                  {#if w.id !== "api"}
                    <div class="state" class:good={c?.installed && c?.signedIn} class:bad={c && (!c.installed || c.signedIn === false)}>
                      {#if !assistant.connections}{assistant.checking ? "Checking…" : ""}
                      {:else if c}{c.installed ? `${c.label} ${c.version ?? ""}: ` : ""}{c.detail}{/if}
                    </div>
                  {/if}
                </div>
              </label>
            {/each}
          </div>
          <div class="row"><span></span><button class="link" disabled={assistant.checking} onclick={checkConnections}>{assistant.checking ? "Checking…" : "Check Claude Code and Codex again"}</button></div>

          {#if a.provider === "api"}
            <label class="row">
              <span>Model</span>
              <select class="select" value={a.model} onchange={(e) => configure({ model: (e.target as HTMLSelectElement).value })}>
                {#each a.models as m (m)}<option value={m}>{MODEL_NAMES[m] ?? m}</option>{/each}
              </select>
            </label>
            <div class="row">
              <span>API key</span>
              <div class="keyrow">
                <input class="input" type="password" bind:value={key} placeholder={a.keySource === "saved" ? "Saved (type to replace)" : a.keySource === "environment" ? "From ANTHROPIC_API_KEY" : "sk-ant-…"} autocomplete="off" />
                <button class="btn" disabled={!key.trim()} onclick={() => configure({ key }).then(() => (key = ""))}>Save</button>
              </div>
            </div>
            {#if a.keySource === "saved"}
              <div class="row"><span></span><button class="link" onclick={() => configure({ forgetKey: true })}>Forget the saved key</button></div>
            {/if}
          {:else if a.provider === "claude-code"}
            <label class="row">
              <span>Model</span>
              <select class="select" value={a.claudeCodeModel} onchange={(e) => configure({ claudeCodeModel: (e.target as HTMLSelectElement).value })}>
                {#each a.claudeCodeModels as m (m)}<option value={m}>{CLAUDE_CODE_MODELS[m] ?? m}</option>{/each}
              </select>
            </label>
          {:else}
            <label class="row">
              <span>Model</span>
              <input class="input" value={a.codexModel} placeholder="Codex's default" onchange={(e) => configure({ codexModel: (e.target as HTMLInputElement).value })} />
            </label>
          {/if}
        {/if}
        <p class="note">
          Off unless you turn it on. Your messages, the open design's data and the tools' results go to
          {a.provider === "codex" ? "OpenAI" : "Anthropic"}{a.provider === "api" ? ", and use is charged to your API account. The key is kept in " + a.settingsFile + ", not in the designs folder." : ". Use counts against your plan's limits, as it does in a terminal; RocketMaxxing never sees your sign-in."}
          {#if a.provider !== "api"}Claude Code runs without its own tools (no shell, files or web), Codex in its read-only sandbox: they work only through RocketMaxxing's tools.{/if}
        </p>
      {/if}

      <h4>Files</h4>
      <div class="row"><span>Designs folder</span><code>{app.session?.workspace}</code></div>
      <p class="note">RocketMaxxing {app.session?.version}, engine {app.session?.engineVersion}, OpenRocket 24.12</p>
    </div>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 30%);
    display: grid;
    place-items: center;
    z-index: 40;
  }
  .dlg {
    width: min(520px, calc(100vw - 32px));
    max-height: calc(100vh - 40px);
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 8px 8px 16px;
    border-bottom: 1px solid var(--line);
  }
  h3 {
    margin: 0;
    font-size: 15px;
  }
  .content {
    overflow: auto;
    padding: 4px 16px 16px;
  }
  h4 {
    margin: 16px 0 6px;
    font-size: 13px;
  }
  .row {
    display: grid;
    grid-template-columns: 1fr 200px;
    align-items: center;
    gap: 10px;
    min-height: 32px;
    color: var(--ink-2);
  }
  .row.check input {
    justify-self: start;
    width: 16px;
    height: 16px;
  }
  .ways {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 4px 0;
  }
  .way {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    padding: 8px 10px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    cursor: pointer;
  }
  .way.on {
    border-color: var(--select);
    background: var(--surface-2);
  }
  .way input {
    margin-top: 3px;
  }
  .way .note {
    margin: 2px 0 0;
  }
  .state {
    font-size: 12px;
    margin-top: 3px;
    color: var(--ink-3);
  }
  .state.good {
    color: var(--good);
  }
  .state.bad {
    color: var(--warn);
  }
  .keyrow {
    display: flex;
    gap: 6px;
  }
  .keyrow .input {
    flex: 1;
    min-width: 0;
  }
  .link {
    justify-self: start;
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0;
  }
  .units {
    display: flex;
    flex-direction: column;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .seg button {
    flex: 1;
    border: 0;
    background: var(--surface);
    padding: 5px 0;
    color: var(--ink-2);
  }
  .seg button.on {
    background: var(--select);
    color: #fff;
  }
  .note {
    margin: 4px 0;
    font-size: 12px;
    color: var(--ink-3);
  }
  code {
    font-size: 12px;
    overflow-wrap: anywhere;
  }
</style>
