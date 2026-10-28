<script lang="ts">
  import { House, Rocket, Flame, ChartSpline } from "@lucide/svelte";
  import { app, type Page } from "./state.svelte";

  const pages: { id: Page; label: string; hint: string; icon: typeof Rocket }[] = [
    { id: "home", label: "Start", hint: "New, open and example designs", icon: House },
    { id: "design", label: "Design", hint: "Parts, sizes and materials", icon: Rocket },
    { id: "motors", label: "Motors", hint: "Motors, recovery and stages per flight configuration", icon: Flame },
    { id: "flights", label: "Flights", hint: "Simulations and plots", icon: ChartSpline },
  ];
</script>

<nav>
  {#each pages as p (p.id)}
    {@const Icon = p.icon}
    <button
      class:on={app.page === p.id}
      disabled={p.id !== "home" && !app.design}
      title={p.hint}
      onclick={() => (app.page = p.id)}
    >
      <Icon size={26} strokeWidth={1.8} />
      <span>{p.label}</span>
    </button>
  {/each}
</nav>

<style>
  nav {
    width: 84px;
    flex: none;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 10px 8px;
    background: var(--surface);
    border-right: 1px solid var(--line);
  }
  button {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 10px 0 8px;
    border: 0;
    border-radius: 12px;
    background: transparent;
    color: var(--ink-2);
    font-size: 12px;
    font-weight: 600;
  }
  button:hover:not(:disabled) {
    background: var(--surface-3);
    color: var(--ink);
  }
  button.on {
    background: var(--accent-soft);
    color: var(--accent);
  }
  button:disabled {
    opacity: 0.35;
  }
</style>
