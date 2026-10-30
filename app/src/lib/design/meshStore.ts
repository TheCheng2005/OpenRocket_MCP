// Which meshes the window keeps (design/meshes.ts builds them): the engine call needs only the ids, and this file
// keeps three.js out of the first download.

export interface Kept {
  geo: { dispose(): void };
  edges: { dispose(): void };
}

export const store = new Map<string, Kept>();

/** Ids of the meshes kept, for the engine to leave out. */
export function knownMeshes(): string[] {
  return [...store.keys()];
}

/** Whether every part's mesh is either in the reply or kept here. */
export function complete(parts: { mesh?: string; meshId?: string }[]): boolean {
  return parts.every((p) => p.mesh !== undefined || (p.meshId !== undefined && store.has(p.meshId)));
}

/** Whether a geometry is kept here (then the view must not dispose it). */
export function kept(meshId: string | undefined): boolean {
  return !!meshId && store.has(meshId);
}
