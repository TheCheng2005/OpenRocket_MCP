<script lang="ts">
  import Tree from "./Tree.svelte";
  import Palette from "./Palette.svelte";
  import Inspector from "./Inspector.svelte";
  import RocketView from "./RocketView.svelte";
  import Stats from "./Stats.svelte";
</script>

<div class="design">
  <aside class="left card">
    <Tree />
  </aside>
  <section class="center">
    <div class="view card">
      <RocketView />
    </div>
    <Stats />
    <div class="palette card">
      <Palette />
    </div>
  </section>
  <aside class="right card">
    <Inspector />
  </aside>
</div>

<style>
  .design {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(220px, 270px) minmax(0, 1fr) minmax(300px, 350px);
    gap: 10px;
    padding: 10px;
  }
  .left,
  .right {
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .center {
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .view {
    flex: 1;
    min-height: 220px;
    overflow: hidden;
    position: relative;
  }
  .palette {
    flex: none;
  }
</style>
