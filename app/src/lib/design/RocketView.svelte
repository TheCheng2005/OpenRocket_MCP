<script lang="ts">
  import { onMount } from "svelte";
  import * as THREE from "three";
  import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
  import { toCreasedNormals } from "three/examples/jsm/utils/BufferGeometryUtils.js";
  import { Maximize, Scissors } from "@lucide/svelte";
  import { app, select, type Part } from "../state.svelte";

  // The rocket as OpenRocket draws it (side view, nose to the left; back view; free 3-D), from the engine's own
  // meshes of every part. Outside parts are see-through in the side view so the inside shows, as in OpenRocket.

  type Mode = "side" | "back" | "3d";
  const MODE_KEY = "rocketmaxxing.view";
  let mode = $state<Mode>((() => {
    try {
      return (localStorage.getItem(MODE_KEY) as Mode) || "side";
    } catch {
      return "side";
    }
  })());
  let cutaway = $state(true);

  let host: HTMLDivElement;
  let canvasHost: HTMLDivElement;
  let cgEl: HTMLDivElement;
  let cpEl: HTMLDivElement;

  let renderer: THREE.WebGLRenderer;
  let scene: THREE.Scene;
  let ortho: THREE.OrthographicCamera;
  let persp: THREE.PerspectiveCamera;
  let camera: THREE.Camera;
  let controls: OrbitControls | null = null;
  let rocket = new THREE.Group();
  let axis: THREE.Line | null = null;
  let length = 1;
  let radius = 0.05;
  let meshes: { mesh: THREE.Mesh; edges: THREE.LineSegments; part: Part }[] = [];
  let webgl = $state(true);

  const css = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || "#888";

  function decode(b64: string): Float32Array {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new Float32Array(bytes.buffer);
  }

  function build(parts: Part[]) {
    for (const m of meshes) {
      m.mesh.geometry.dispose();
      (m.mesh.material as THREE.Material).dispose();
      m.edges.geometry.dispose();
      (m.edges.material as THREE.Material).dispose();
    }
    scene.remove(rocket);
    rocket = new THREE.Group();
    meshes = [];
    let x1 = 0;
    let r = 0;
    const clip = [new THREE.Plane(new THREE.Vector3(0, 0, -1), 0)];
    for (const p of parts) {
      const pos = decode(p.mesh);
      if (pos.length === 0) continue;
      let geo = new THREE.BufferGeometry();
      geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
      geo = toCreasedNormals(geo, Math.PI / 5);
      const mat = new THREE.MeshStandardMaterial({
        color: new THREE.Color(p.color),
        roughness: 0.55,
        metalness: 0.05,
        side: THREE.DoubleSide,
        polygonOffset: true,
        polygonOffsetFactor: 1,
        polygonOffsetUnits: 1,
      });
      if (p.cutOpen) mat.clippingPlanes = clip;
      const mesh = new THREE.Mesh(geo, mat);
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(geo, 30), new THREE.LineBasicMaterial({ color: 0x334155 }));
      if (p.cutOpen) (edges.material as THREE.LineBasicMaterial).clippingPlanes = clip;
      mesh.userData.part = p;
      rocket.add(mesh, edges);
      meshes.push({ mesh, edges, part: p });
      x1 = Math.max(x1, p.x1);
      geo.computeBoundingSphere();
      const bb = new THREE.Box3().setFromBufferAttribute(geo.getAttribute("position") as THREE.BufferAttribute);
      r = Math.max(r, Math.abs(bb.max.y), Math.abs(bb.min.y), Math.abs(bb.max.z), Math.abs(bb.min.z));
    }
    length = Math.max(x1, 0.05);
    radius = Math.max(r, 0.005);
    scene.add(rocket);
    if (axis) {
      scene.remove(axis);
      axis.geometry.dispose();
    }
    const g = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(-0.04 * length, 0, 0), new THREE.Vector3(1.04 * length, 0, 0)]);
    axis = new THREE.Line(g, new THREE.LineDashedMaterial({ color: 0x94a3b8, dashSize: length / 60, gapSize: length / 120 }));
    axis.computeLineDistances();
    scene.add(axis);
    style();
  }

  /** Looks of every part for the current view mode and selection. */
  function style() {
    const sel = new THREE.Color(css("--select"));
    const ink = new THREE.Color(css("--icon-ink"));
    for (const { mesh, edges, part } of meshes) {
      const m = mesh.material as THREE.MeshStandardMaterial;
      const e = edges.material as THREE.LineBasicMaterial;
      const selected = !!part.id && part.id === app.selected;
      const outside = !part.internal;
      if (mode === "3d") {
        mesh.visible = outside || cutaway;
        m.transparent = false;
        m.opacity = 1;
        m.depthWrite = true;
        edges.visible = selected;
        m.clippingPlanes = cutaway && part.cutOpen ? [new THREE.Plane(new THREE.Vector3(0, 0, -1), 0)] : [];
        e.clippingPlanes = m.clippingPlanes;
      } else {
        mesh.visible = true;
        m.clippingPlanes = [];
        e.clippingPlanes = [];
        m.transparent = outside;
        m.opacity = outside ? (selected ? 0.55 : 0.28) : 1;
        m.depthWrite = !outside;
        edges.visible = true;
      }
      m.emissive = selected ? sel.clone().multiplyScalar(0.45) : new THREE.Color(0x000000);
      e.color = selected ? sel : ink;
      e.opacity = selected ? 1 : 0.55;
      e.transparent = !selected;
      mesh.renderOrder = outside ? 2 : 1;
      m.needsUpdate = true;
    }
    if (axis) axis.visible = mode !== "back";
    render();
  }

  function fit() {
    const w = canvasHost.clientWidth || 1;
    const h = canvasHost.clientHeight || 1;
    const aspect = w / h;
    const cx = length / 2;
    if (controls) controls.dispose();
    if (mode === "3d") {
      persp.aspect = aspect;
      persp.near = length / 1000;
      persp.far = length * 100;
      const d = (length * 0.42) / Math.tan(((persp.fov / 2) * Math.PI) / 180) / Math.min(aspect, 1.8);
      persp.position.set(cx + d * 0.32, d * 0.38, d * 0.87);
      persp.up.set(0, 1, 0);
      persp.updateProjectionMatrix();
      camera = persp;
    } else {
      const span = mode === "side" ? length * 1.12 : radius * 3.2;
      let halfW = span / 2;
      let halfH = halfW / aspect;
      const needH = mode === "side" ? radius * 2.6 : radius * 1.6;
      if (halfH < needH) {
        halfH = needH;
        halfW = halfH * aspect;
      }
      ortho.left = -halfW;
      ortho.right = halfW;
      ortho.top = halfH;
      ortho.bottom = -halfH;
      ortho.near = -length * 10;
      ortho.far = length * 10;
      ortho.zoom = 1;
      ortho.up.set(0, 1, 0);
      if (mode === "side") ortho.position.set(cx, 0, length * 3);
      else ortho.position.set(length * 4, 0, 0);
      ortho.updateProjectionMatrix();
      camera = ortho;
    }
    controls = new OrbitControls(camera, renderer.domElement);
    controls.target.set(mode === "back" ? length : cx, 0, 0);
    controls.enableDamping = false;
    controls.enableRotate = mode === "3d";
    controls.screenSpacePanning = true;
    if (mode !== "3d") controls.mouseButtons = { LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN };
    controls.zoomToCursor = true;
    controls.update();
    controls.addEventListener("change", render);
    render();
  }

  function marker(el: HTMLDivElement, x: number | null | undefined) {
    if (!el) return;
    if (x === null || x === undefined || !Number.isFinite(x) || mode === "back" || !camera) {
      el.style.display = "none";
      return;
    }
    const v = new THREE.Vector3(x, 0, 0).project(camera);
    if (v.z < -1 || v.z > 1) {
      el.style.display = "none";
      return;
    }
    el.style.display = "block";
    el.style.left = `${((v.x + 1) / 2) * canvasHost.clientWidth}px`;
    el.style.top = `${((1 - v.y) / 2) * canvasHost.clientHeight}px`;
  }

  function render() {
    if (!renderer || !camera) return;
    scene.background = new THREE.Color(css("--view-bg"));
    renderer.render(scene, camera);
    const st = app.model?.stability;
    marker(cgEl, st?.cg);
    marker(cpEl, st?.cp);
  }

  function resize() {
    if (!renderer) return;
    renderer.setSize(canvasHost.clientWidth, canvasHost.clientHeight, false);
    fit();
  }

  let down: { x: number; y: number } | null = null;

  function pick(e: PointerEvent) {
    if (!down || Math.hypot(e.clientX - down.x, e.clientY - down.y) > 4) return;
    const rect = renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
    const ray = new THREE.Raycaster();
    ray.setFromCamera(ndc, camera);
    const hits = ray.intersectObjects(meshes.filter((m) => m.mesh.visible && m.part.id).map((m) => m.mesh), false);
    if (!hits.length) {
      select("rocket");
      return;
    }
    let best = hits[0];
    if (mode !== "3d") {
      // Seen through the airframe: the innermost part under the pointer is the one meant.
      for (const h of hits) if ((h.object.userData.part as Part).depth > (best.object.userData.part as Part).depth) best = h;
    }
    const id = (best.object.userData.part as Part).id;
    if (id) select(id);
  }

  function setMode(m: Mode) {
    mode = m;
    try {
      localStorage.setItem(MODE_KEY, m);
    } catch {
      /* not kept */
    }
    style();
    fit();
  }

  onMount(() => {
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true });
    } catch {
      webgl = false;
      return;
    }
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    renderer.localClippingEnabled = true;
    canvasHost.appendChild(renderer.domElement);
    scene = new THREE.Scene();
    scene.add(new THREE.HemisphereLight(0xffffff, 0x8a94a6, 1.6));
    const sun = new THREE.DirectionalLight(0xffffff, 1.6);
    sun.position.set(0.4, 1, 0.8);
    scene.add(sun);
    const back = new THREE.DirectionalLight(0xffffff, 0.5);
    back.position.set(-0.5, -0.3, -1);
    scene.add(back);
    ortho = new THREE.OrthographicCamera(-1, 1, 1, -1, -10, 10);
    persp = new THREE.PerspectiveCamera(35, 1, 0.01, 1000);
    camera = ortho;
    renderer.domElement.addEventListener("pointerdown", (e) => (down = { x: e.clientX, y: e.clientY }));
    renderer.domElement.addEventListener("pointerup", pick);
    const ro = new ResizeObserver(resize);
    ro.observe(canvasHost);
    const mq = matchMedia("(prefers-color-scheme: dark)");
    const theme = new MutationObserver(() => style());
    theme.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    mq.addEventListener("change", style);
    if (app.model) {
      build(app.model.parts);
      fit();
    }
    return () => {
      ro.disconnect();
      theme.disconnect();
      mq.removeEventListener("change", style);
      controls?.dispose();
      renderer.dispose();
    };
  });

  // Rebuild when the engine sends a new model; restyle when the selection changes.
  let lastModel: unknown = null;
  let lastLength = 0;
  $effect(() => {
    const m = app.model;
    if (!scene || !m || m === lastModel) return;
    lastModel = m;
    build(m.parts);
    // Keep the camera where the user put it unless the rocket's size changed a lot (a new design, a big edit).
    if (Math.abs(length - lastLength) > 0.25 * Math.max(length, lastLength) || !lastLength) fit();
    else render();
    lastLength = length;
  });
  $effect(() => {
    void app.selected;
    if (scene) style();
  });
</script>

<div class="rv" bind:this={host}>
  <div class="canvas" bind:this={canvasHost}></div>
  {#if !webgl}
    <div class="nogl">This computer cannot show 3-D graphics (WebGL is off).</div>
  {/if}
  <div class="marker cg" bind:this={cgEl} title="Centre of gravity (at launch)"><span>CG</span></div>
  <div class="marker cp" bind:this={cpEl} title="Centre of pressure (Mach 0.3)"><span>CP</span></div>
  <div class="bar">
    <div class="seg" role="group" aria-label="View">
      <button class:on={mode === "side"} onclick={() => setMode("side")}>Side</button>
      <button class:on={mode === "back"} onclick={() => setMode("back")}>Back</button>
      <button class:on={mode === "3d"} onclick={() => setMode("3d")}>3D</button>
    </div>
    {#if mode === "3d"}
      <button class="btn small" class:on={cutaway} onclick={() => ((cutaway = !cutaway), style())} title="Cut the airframe open to show the inside">
        <Scissors size={15} /> Cut-away
      </button>
    {/if}
    <button class="btn small" onclick={fit} title="Fit the rocket in the view"><Maximize size={15} /> Fit</button>
  </div>
  <div class="hint muted">
    {mode === "3d" ? "Drag to turn · right-drag to move · scroll to zoom · click a part to edit it" : "Drag to move · scroll to zoom · click a part to edit it"}
  </div>
</div>

<style>
  .rv {
    position: absolute;
    inset: 0;
  }
  .canvas {
    position: absolute;
    inset: 0;
  }
  .canvas :global(canvas) {
    width: 100%;
    height: 100%;
    display: block;
  }
  .bar {
    position: absolute;
    top: 10px;
    right: 10px;
    display: flex;
    gap: 6px;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: 8px;
    overflow: hidden;
    background: var(--surface);
    box-shadow: var(--shadow);
  }
  .seg button {
    border: 0;
    background: transparent;
    padding: 6px 14px;
    font-weight: 600;
    color: var(--ink-2);
  }
  .seg button.on {
    background: var(--ink);
    color: var(--surface);
  }
  .btn.small {
    height: 32px;
    box-shadow: var(--shadow);
  }
  .btn.small.on {
    background: var(--select-soft);
    border-color: var(--select);
  }
  .hint {
    position: absolute;
    left: 12px;
    bottom: 8px;
    font-size: 11.5px;
    pointer-events: none;
  }
  .marker {
    position: absolute;
    width: 22px;
    height: 22px;
    margin: -11px 0 0 -11px;
    border-radius: 50%;
    pointer-events: none;
    display: none;
  }
  .marker span {
    position: absolute;
    top: -20px;
    left: 50%;
    transform: translateX(-50%);
    font-size: 11px;
    font-weight: 700;
    padding: 0 4px;
    border-radius: 4px;
    background: var(--surface);
  }
  /* OpenRocket's symbols: CG a blue and white quartered disc, CP a red ring with a dot. */
  .cg {
    border: 2px solid #1d4ed8;
    background: conic-gradient(#1d4ed8 0 25%, #fff 0 50%, #1d4ed8 0 75%, #fff 0);
  }
  .cg span {
    color: #1d4ed8;
  }
  .cp {
    border: 3px solid #dc2626;
    background: radial-gradient(circle, #dc2626 0 3px, transparent 4px);
  }
  .cp span {
    color: #dc2626;
  }
  .nogl {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: var(--ink-3);
  }
</style>
