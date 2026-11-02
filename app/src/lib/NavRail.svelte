<script lang="ts">
  import { House, Rocket, Flame, ChartSpline, FileChartLine, Target, BookOpenCheck, ClipboardCheck } from "@lucide/svelte";
  import { app, type Page } from "./state.svelte";

  const pages: { id: Page; label: string; hint: string; icon: typeof Rocket }[] = [
    { id: "home", label: "Start", hint: "Recent files and examples", icon: House },
    { id: "design", label: "Design", hint: "Rocket design", icon: Rocket },
    { id: "motors", label: "Motors", hint: "Motors & configuration", icon: Flame },
    { id: "flights", label: "Flights", hint: "Flight simulations", icon: ChartSpline },
    { id: "targets", label: "Targets", hint: "The team's targets and the competition rules", icon: Target },
    { id: "standards", label: "Standards", hint: "Rule set, launch site, safety factors, ratings and materials", icon: BookOpenCheck },
    { id: "analysis", label: "Analysis", hint: "Rule check, recovery, structures, studies", icon: ClipboardCheck },
    { id: "flightdata", label: "Flight data", hint: "Altimeter logs and past rockets", icon: FileChartLine },
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
      <Icon size={24} strokeWidth={1.7} />
      <span>{p.label}</span>
    </button>
  {/each}
</nav>

<style>
  nav {
    width: 72px;
    flex: none;
    display: flex;
    flex-direction: column;
    background: var(--chrome);
    border-right: 1px solid var(--chrome-line);
  }
  button {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 3px;
    padding: 10px 0 8px;
    border: 0;
    border-left: 3px solid transparent;
    background: transparent;
    color: var(--chrome-ink-2);
    font-size: 11.5px;
  }
  button:hover:not(:disabled) {
    background: var(--chrome-2);
    color: var(--chrome-ink);
  }
  button.on {
    border-left-color: var(--chrome-mark);
    background: var(--chrome-3);
    color: var(--chrome-ink);
    font-weight: 600;
  }
  button:disabled {
    opacity: 0.35;
  }
</style>
