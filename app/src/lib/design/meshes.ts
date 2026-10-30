// The geometry of each part, kept by the engine's mesh id. An edit usually changes one part: the engine sends only
// the meshes the window does not have, and the creased normals and outline edges (most of a rebuild's time) are
// computed once per mesh instead of for every part after every edit.

import * as THREE from "three";
import { toCreasedNormals } from "three/examples/jsm/utils/BufferGeometryUtils.js";
import { store } from "./meshStore";

export { kept } from "./meshStore";

export interface Built {
  geo: THREE.BufferGeometry;
  edges: THREE.BufferGeometry;
}

const cache = store as Map<string, Built>;
/** Most meshes kept; a design has a few dozen parts, so this covers several edits back (undo lands on a kept one). */
const MAX = 240;

function decode(b64: string): Float32Array {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Float32Array(bytes.buffer);
}

/** The part's geometry: kept, or built from the mesh in the reply. Null for an empty mesh. */
export function geometry(meshId: string | undefined, mesh: string | undefined): Built | null {
  if (meshId && cache.has(meshId)) {
    const b = cache.get(meshId)!;
    cache.delete(meshId); // most recently used last
    cache.set(meshId, b);
    return b;
  }
  if (mesh === undefined) return null;
  const pos = decode(mesh);
  if (pos.length === 0) return null;
  let geo = new THREE.BufferGeometry();
  geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  geo = toCreasedNormals(geo, Math.PI / 5);
  geo.computeBoundingSphere();
  geo.computeBoundingBox();
  const b = { geo, edges: new THREE.EdgesGeometry(geo, 30) };
  if (meshId) cache.set(meshId, b);
  return b;
}

/** Frees the least recently used meshes beyond the limit, except those on screen. */
export function trim(inUse: Set<string>): void {
  for (const [id, b] of cache) {
    if (cache.size <= MAX) break;
    if (inUse.has(id)) continue;
    b.geo.dispose();
    b.edges.dispose();
    cache.delete(id);
  }
}
