<script lang="ts">
  import { app } from "./state.svelte";
</script>

<div class="toasts" aria-live="polite">
  {#each app.toasts as t (t.id)}
    <div class="toast {t.kind}">{t.text}</div>
  {/each}
</div>

<style>
  .toasts {
    position: fixed;
    right: 16px;
    bottom: 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    z-index: 50;
    max-width: 440px;
  }
  .toast {
    padding: 7px 12px;
    border-radius: var(--radius);
    background: var(--ink);
    color: var(--surface);
    box-shadow: var(--shadow-pop);
    font-size: 13px;
  }
  .toast.error {
    background: var(--bad);
    color: #fff;
  }
</style>
