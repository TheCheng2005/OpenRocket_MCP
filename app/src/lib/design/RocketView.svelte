<script lang="ts">
  import { onMount } from "svelte";
  import * as THREE from "three";
  import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
  import { geometry, kept, trim } from "./meshes";
  import { Maximize, Scissors } from "@lucide/svelte";
  import { app, select, findNode, type Part, type TreeNode } from "../state.svelte";

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

  function build(parts: Part[]) {
    for (const m of meshes) {
      // Geometry is kept by mesh id (design/meshes.ts) and reused by the next build; only materials are per build.
      if (!kept(m.part.meshId)) {
        m.mesh.geometry.dispose();
        m.edges.geometry.dispose();
      }
      (m.mesh.material as THREE.Material).dispose();
      (m.edges.material as THREE.Material).dispose();
    }
    scene.remove(rocket);
    rocket = new THREE.Group();
    meshes = [];
    let x1 = 0;
    let r = 0;
    const clip = [new THREE.Plane(new THREE.Vector3(0, 0, -1), 0)];
    const inUse = new Set<string>();
    for (const p of parts) {
      const built = geometry(p.meshId, p.mesh);
      if (!built) continue;
      if (p.meshId) inUse.add(p.meshId);
      const geo = built.geo;
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
      const edges = new THREE.LineSegments(built.edges, new THREE.LineBasicMaterial({ color: 0x334155 }));
      if (p.cutOpen) (edges.material as THREE.LineBasicMaterial).clippingPlanes = clip;
      mesh.userData.part = p;
      rocket.add(mesh, edges);
      meshes.push({ mesh, edges, part: p });
      x1 = Math.max(x1, p.x1);
      const bb = geo.boundingBox!;
      r = Math.max(r, Math.abs(bb.max.y), Math.abs(bb.min.y), Math.abs(bb.max.z), Math.abs(bb.min.z));
    }
    trim(inUse);
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

  /**
   * The parts a tree node stands for in the view: the part itself, or, for a stage, pod set or other group that has
   * no shape of its own, everything in it.
   */
  function shapesOf(id: string | null): Set<string> {
    const out = new Set<string>();
    if (!id || id === "rocket") return out;
    if (meshes.some((m) => m.part.id === id)) {
      out.add(id);
      return out;
    }
    const walk = (n: TreeNode) => {
      out.add(n.id);
      n.children.forEach(walk);
    };
    const n = findNode(app.design?.tree, id);
    if (n) walk(n);
    return out;
  }

  /** 0..1 for the pulse after a selection (1 at the start, gone after PULSE ms). */
  const PULSE = 700;
  let pulse = 0;
  let pulseFrame = 0;

  function startPulse() {
    cancelAnimationFrame(pulseFrame);
    const t0 = performance.now();
    const step = () => {
      const k = (performance.now() - t0) / PULSE;
      pulse = k >= 1 ? 0 : Math.sin(Math.PI * Math.min(1, k * 1.6)) * (1 - k);
      glow();
      if (k < 1) pulseFrame = requestAnimationFrame(step);
    };
    pulseFrame = requestAnimationFrame(step);
  }

  /** Only the selected parts' tint, for the pulse (the rest of the look does not change frame to frame). */
  function glow() {
    const sel = new THREE.Color(css("--select"));
    const chosen = shapesOf(app.selected);
    for (const { mesh, part } of meshes) {
      if (part.id && chosen.has(part.id)) (mesh.material as THREE.MeshStandardMaterial).emissive = sel.clone().multiplyScalar(0.5 + 0.45 * pulse);
    }
    render();
  }

  /**
   * Looks of every part for the current view mode, selection and preview. The selected part is tinted and outlined
   * and the rest dims; an inside part shows through the airframe (x-ray) even without the cut-away. A previewed part
   * (pointed at in the tree, or where Add part would put a new one) gets a lighter tint and outline.
   */
  function style() {
    const sel = new THREE.Color(css("--select"));
    const ink = new THREE.Color(css("--icon-ink"));
    const chosen = shapesOf(app.selected);
    const previewed = shapesOf(app.hover);
    const focus = chosen.size > 0;
    for (const { mesh, edges, part } of meshes) {
      const m = mesh.material as THREE.MeshStandardMaterial;
      const e = edges.material as THREE.LineBasicMaterial;
      const selected = !!part.id && chosen.has(part.id);
      const hovered = !selected && !!part.id && previewed.has(part.id);
      const outside = !part.internal;
      let opacity = 1;
      let xray = false;
      if (mode === "3d") {
        // An inside part that is selected is drawn through the airframe instead of hiding behind it.
        xray = selected && !outside && !cutaway;
        mesh.visible = outside || cutaway || xray || hovered;
        if (focus && !selected) opacity = outside ? 0.4 : 0.55;
        edges.visible = selected || hovered;
        m.clippingPlanes = cutaway && part.cutOpen ? [new THREE.Plane(new THREE.Vector3(0, 0, -1), 0)] : [];
        e.clippingPlanes = m.clippingPlanes;
      } else {
        mesh.visible = true;
        m.clippingPlanes = [];
        e.clippingPlanes = [];
        opacity = outside ? (selected ? 0.6 : focus ? 0.16 : 0.28) : selected || !focus ? 1 : 0.45;
        edges.visible = true;
      }
      m.transparent = opacity < 1 || xray;
      m.opacity = xray ? 0.92 : opacity;
      m.depthWrite = !m.transparent;
      m.depthTest = !xray;
      e.depthTest = !xray;
      const glow = selected ? 0.5 + 0.45 * pulse : hovered ? 0.28 : 0;
      m.emissive = glow ? sel.clone().multiplyScalar(glow) : new THREE.Color(0x000000);
      e.color = selected || hovered ? sel : ink;
      e.opacity = selected ? 1 : hovered ? 0.8 : focus ? 0.3 : 0.55;
      e.transparent = !selected;
      mesh.renderOrder = xray ? 10 : outside ? 2 : 1;
      edges.renderOrder = xray ? 11 : 0;
      m.needsUpdate = true;
    }
    if (axis) axis.visible = mode !== "back";
    frame(chosen, previewed);
    render();
  }

  /**
   * In the side and back views, a box around the selected part (and a fainter one around a previewed part), drawn
   * over everything: through a see-through airframe a tint alone is easy to miss.
   */
  let boxes = new THREE.Group();
  function frame(chosen: Set<string>, previewed: Set<string>) {
    scene.remove(boxes);
    boxes.traverse((o) => {
      if (o instanceof THREE.LineSegments) {
        o.geometry.dispose();
        (o.material as THREE.Material).dispose();
      }
    });
    boxes = new THREE.Group();
    if (mode !== "3d") {
      const sel = new THREE.Color(css("--select"));
      const pad = radius * 0.12;
      for (const [ids, opacity] of [[chosen, 1], [previewed, 0.55]] as const) {
        const box = new THREE.Box3();
        for (const { mesh, part } of meshes) if (part.id && ids.has(part.id)) box.expandByObject(mesh);
        if (box.isEmpty() || (ids === previewed && [...previewed].every((id) => chosen.has(id)))) continue;
        box.expandByScalar(pad);
        const helper = new THREE.Box3Helper(box, sel);
        const mat = helper.material as THREE.LineBasicMaterial;
        mat.depthTest = false;
        mat.transparent = opacity < 1;
        mat.opacity = opacity;
        helper.renderOrder = 20;
        boxes.add(helper);
      }
    }
    scene.add(boxes);
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
    // Zoom limits: never through the rocket or out of sight, however fast the wheel or the trackpad.
    minDist = Math.max(radius * 1.8, length * 0.02); // outside the airframe when orbiting its axis
    maxDist = length * 8;
    maxZoom = Math.max(4, ortho.top / (radius * 0.2));
    controls = new OrbitControls(camera, renderer.domElement);
    controls.minDistance = minDist;
    controls.maxDistance = maxDist;
    controls.minZoom = MIN_ZOOM;
    controls.maxZoom = maxZoom;
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

  // Wheel and trackpad zoom, done here rather than by OrbitControls, which zooms each event in one step (a fast flick
  // or a trackpad pinch, which it multiplies by 10, jumped) and could carry the camera through the point it orbits.
  // Each event's share is capped, a fling is eased over a few frames, and it zooms toward the pointer within limits.
  const MIN_ZOOM = 0.5;
  let minDist = 0.01;
  let maxDist = 100;
  let maxZoom = 50;
  let zoomLeft = 0; // log of the zoom still to apply
  let zoomPoint = { x: 0, y: 0 };
  let zoomFrame = 0;

  function wheel(e: WheelEvent) {
    if (!controls || !camera) return;
    e.preventDefault();
    e.stopPropagation(); // OrbitControls listens on the canvas inside: it does not get the wheel
    let dy = e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 100 : 1);
    if (e.ctrlKey) dy *= 3; // a trackpad pinch arrives as small wheel steps with Ctrl
    dy = Math.max(-120, Math.min(120, dy));
    zoomLeft = Math.max(-1.2, Math.min(1.2, zoomLeft - dy * 0.0022));
    zoomPoint = { x: e.clientX, y: e.clientY };
    if (!zoomFrame) zoomFrame = requestAnimationFrame(zoomStep);
  }

  function zoomStep() {
    zoomFrame = 0;
    let step = zoomLeft * 0.35;
    if (Math.abs(zoomLeft) < 0.003) step = zoomLeft;
    zoomLeft -= step;
    zoomBy(Math.exp(step), zoomPoint.x, zoomPoint.y);
    if (zoomLeft !== 0) zoomFrame = requestAnimationFrame(zoomStep);
  }

  /** Zooms by {@code f} (above 1: closer) keeping the point under the pointer where it is. */
  function zoomBy(f: number, px: number, py: number) {
    if (!controls) return;
    const rect = renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector3(((px - rect.left) / rect.width) * 2 - 1, -((py - rect.top) / rect.height) * 2 + 1, 0);
    if (camera === ortho) {
      const before = ndc.clone().unproject(ortho);
      ortho.zoom = Math.max(MIN_ZOOM, Math.min(maxZoom, ortho.zoom * f));
      ortho.updateProjectionMatrix();
      const shift = before.sub(ndc.clone().unproject(ortho));
      ortho.position.add(shift);
      controls.target.add(shift);
    } else {
      // Toward the point under the pointer on the plane through the orbit centre, which moves along, so the camera
      // closes in on what is pointed at and never passes the centre.
      const ray = new THREE.Raycaster();
      ray.setFromCamera(new THREE.Vector2(ndc.x, ndc.y), persp);
      const plane = new THREE.Plane().setFromNormalAndCoplanarPoint(persp.getWorldDirection(new THREE.Vector3()), controls.target);
      const p = ray.ray.intersectPlane(plane, new THREE.Vector3()) ?? controls.target.clone();
      const d = persp.position.distanceTo(controls.target);
      const r = Math.max(minDist, Math.min(maxDist, d / f)) / d;
      persp.position.sub(p).multiplyScalar(r).add(p);
      controls.target.sub(p).multiplyScalar(r).add(p);
    }
    controls.update();
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

  /** Runs after the browser has painted: the tree, numbers and page show first, the 3-D work comes a frame later. */
  function afterPaint(f: () => void) {
    requestAnimationFrame(() => setTimeout(f, 0));
  }

  onMount(() => {
    let stopped = false;
    let cleanup: (() => void) | undefined;
    afterPaint(() => {
      if (!stopped) cleanup = setup();
    });
    return () => {
      stopped = true;
      cleanup?.();
    };
  });

  function setup(): (() => void) | undefined {
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
    canvasHost.addEventListener("wheel", wheel, { passive: false, capture: true });
    const mq = matchMedia("(prefers-color-scheme: dark)");
    const theme = new MutationObserver(() => style());
    theme.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme", "data-scheme", "data-dark"] });
    mq.addEventListener("change", style);
    if (app.model) {
      lastModel = app.model;
      build(app.model.parts);
      fit();
      lastLength = length;
    }
    return () => {
      ro.disconnect();
      canvasHost.removeEventListener("wheel", wheel, { capture: true });
      cancelAnimationFrame(zoomFrame);
      cancelAnimationFrame(pulseFrame);
      theme.disconnect();
      mq.removeEventListener("change", style);
      controls?.dispose();
      renderer.dispose();
    };
  }

  // Rebuild when the engine sends a new model; restyle when the selection changes.
  let lastModel: unknown = null;
  let lastLength = 0;
  $effect(() => {
    const m = app.model;
    if (!scene || !m || m === lastModel) return;
    afterPaint(() => {
      if (m !== app.model || m === lastModel) return; // a newer model came meanwhile, or this one is built
      lastModel = m;
      build(m.parts);
      // Keep the camera where the user put it unless the rocket's size changed a lot (a new design, a big edit).
      if (Math.abs(length - lastLength) > 0.25 * Math.max(length, lastLength) || !lastLength) fit();
      else render();
      lastLength = length;
    });
  });
  $effect(() => {
    void app.selected;
    void app.hover;
    if (scene) style();
  });
  // A new selection pulses briefly, so the eye finds it.
  $effect(() => {
    const f = app.flash;
    if (f && scene) startPulse();
  });
</script>

<div class="rv" bind:this={host}>
  <div class="canvas" bind:this={canvasHost} title={mode === "3d" ? "Drag to turn, right-drag to move, scroll to zoom" : "Drag to move, scroll to zoom"}></div>
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
        <Scissors size={14} /> Cut-away
      </button>
    {/if}
    <button class="btn small" onclick={fit} title="Fit the rocket in the view"><Maximize size={14} /> Fit</button>
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
    top: 6px;
    right: 6px;
    display: flex;
    gap: 4px;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--surface);
  }
  .seg button {
    border: 0;
    border-left: 1px solid var(--line);
    background: transparent;
    padding: 3px 12px;
    color: var(--ink-2);
  }
  .seg button:first-child {
    border-left: 0;
  }
  .seg button.on {
    background: var(--select-soft);
    color: var(--ink);
    font-weight: 600;
  }
  .btn.small {
    height: 28px;
  }
  .btn.small.on {
    background: var(--select-soft);
    border-color: var(--select);
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
