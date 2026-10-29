<script lang="ts">
  import { app } from "../state.svelte";
  import TreeNode from "./TreeNode.svelte";

  let root = $derived(app.design?.tree);
</script>

<div class="tree" role="tree" aria-label="Parts">
  {#if root}
    <TreeNode node={root} depth={0} />
  {/if}
</div>

<style>
  .tree {
    flex: 1;
    overflow: auto;
    padding: 3px 0;
  }
</style>
