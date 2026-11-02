// The parts list's shared state: which rows are folded, and the part being dragged and where it would land.

import { app, findNode, parentOf, type TreeNode } from "../state.svelte";

export const tree = $state({
  folded: new Set<string>(),
  /** The part being dragged in the list. */
  dragging: null as { id: string; type: string } | null,
  /** Where it would go if dropped now. */
  drop: null as { id: string; zone: Zone } | null,
});

export type Zone = "before" | "after" | "into";

export function toggleFold(id: string) {
  const f = new Set(tree.folded);
  if (f.has(id)) f.delete(id);
  else f.add(id);
  tree.folded = f;
}

/** The rows shown, top to bottom (children of folded rows left out). */
export function visibleRows(n: TreeNode | undefined, out: TreeNode[] = []): TreeNode[] {
  if (!n) return out;
  out.push(n);
  if (!tree.folded.has(n.id)) for (const c of n.children) visibleRows(c, out);
  return out;
}

function inside(n: TreeNode, id: string): boolean {
  return n.id === id || n.children.some((c) => inside(c, id));
}

/**
 * Where the dragged part would land over this row, from how far down the row the pointer is: the top quarter puts it
 * before the row, the bottom quarter after it, the middle inside it. Null where it cannot go (into itself, or into a
 * part that cannot hold it); the nearest zone that works is used instead.
 */
export function zoneAt(node: TreeNode, frac: number): Zone | null {
  const d = tree.dragging;
  const root = app.design?.tree;
  if (!d || !root) return null;
  const dragged = findNode(root, d.id);
  if (!dragged || inside(dragged, node.id)) return null;
  const parent = parentOf(root, node.id);
  const can = {
    into: node.accepts.includes(d.type),
    before: !!parent && parent.accepts.includes(d.type),
    after: !!parent && parent.accepts.includes(d.type),
  };
  const want: Zone = frac < 0.28 ? "before" : frac > 0.72 ? "after" : "into";
  if (can[want]) return want;
  if (want === "into") return can.after ? (frac < 0.5 ? "before" : "after") : null;
  return can.into ? "into" : null;
}

/** The move a drop makes: [parent, index counted before the move]. */
export function dropTarget(id: string, zone: Zone): [string, number] | null {
  const root = app.design?.tree;
  const node = findNode(root, id);
  if (!root || !node) return null;
  if (zone === "into") return [node.id, node.children.length];
  const parent = parentOf(root, id);
  if (!parent) return null;
  const i = parent.children.findIndex((c) => c.id === id);
  return [parent.id, zone === "before" ? i : i + 1];
}
