package io.github.openrocketmcp.report;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small software renderer for the 3-D views and the flight animation, so they need no graphics card, OpenGL or
 * native library (the Claude Desktop extension runs headless on a bundled Java).
 *
 * <p>Triangles are depth-buffered at a supersampled resolution and box-filtered down, which gives clean anti-aliased
 * edges. Surfaces are Gouraud shaded from vertex normals that are smoothed across edges below a crease angle, so
 * bodies of revolution look round while fin edges stay sharp. Lighting is one directional light, an ambient term and
 * a Blinn highlight; both sides of a surface are lit (hollow tubes show their inside in exploded views).
 */
public final class Raster3d {
	/** A triangle mesh with one base colour (RGB); {@code emissive} meshes (flames) are not lit. */
	public static final class Mesh {
		final List<double[]> tris; // 9 numbers each
		final int rgb;
		final boolean emissive;
		// 9 per triangle, computed on first use; volatile because frames render on several threads at once
		private volatile float[] normals;

		public Mesh(List<double[]> tris, int rgb, boolean emissive) {
			this.tris = tris;
			this.rgb = rgb;
			this.emissive = emissive;
		}

		public Mesh(List<double[]> tris, int rgb) {
			this(tris, rgb, false);
		}

		/** Vertex normals, averaged over the faces around each vertex that meet at less than 40 degrees. */
		float[] normals() {
			if (normals != null) {
				return normals;
			}
			int n = tris.size();
			double[][] fn = new double[n][];
			Map<String, List<Integer>> around = new HashMap<>();
			for (int i = 0; i < n; i++) {
				double[] t = tris.get(i);
				fn[i] = faceNormal(t);
				for (int v = 0; v < 3; v++) {
					around.computeIfAbsent(key(t, v), k -> new ArrayList<>()).add(i);
				}
			}
			float[] out = new float[9 * n];
			double crease = Math.cos(Math.toRadians(40));
			for (int i = 0; i < n; i++) {
				double[] t = tris.get(i);
				for (int v = 0; v < 3; v++) {
					double x = 0, y = 0, z = 0;
					for (int j : around.get(key(t, v))) {
						double d = fn[i][0] * fn[j][0] + fn[i][1] * fn[j][1] + fn[i][2] * fn[j][2];
						if (d >= crease) {
							x += fn[j][0];
							y += fn[j][1];
							z += fn[j][2];
						}
					}
					double l = Math.sqrt(x * x + y * y + z * z);
					if (l < 1e-12) {
						x = fn[i][0];
						y = fn[i][1];
						z = fn[i][2];
						l = 1;
					}
					out[9 * i + 3 * v] = (float) (x / l);
					out[9 * i + 3 * v + 1] = (float) (y / l);
					out[9 * i + 3 * v + 2] = (float) (z / l);
				}
			}
			normals = out;
			return out;
		}

		private static String key(double[] t, int v) {
			return Math.round(t[3 * v] * 1e5) + ":" + Math.round(t[3 * v + 1] * 1e5) + ":" + Math.round(t[3 * v + 2] * 1e5);
		}
	}

	/** A mesh placed in the world: world = origin + rot * local (rot is row-major 3 x 3). */
	public record Placed(Mesh mesh, double[] rot, double[] origin) {
		public static final double[] IDENTITY = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };

		public static Placed at(Mesh m, double dx, double dy, double dz) {
			return new Placed(m, IDENTITY, new double[] { dx, dy, dz });
		}
	}

	/** Pinhole (or orthographic) camera. Up is +z unless set. */
	public static final class Camera {
		double[] eye, fwd, right, up;
		double fovDeg = 40;
		boolean ortho;
		double orthoScale = 1; // pixels per metre (final resolution) in orthographic mode
		double orthoCx, orthoCy; // view-plane coordinates at the image centre
		double near = 0.02;

		public static Camera lookAt(double[] eye, double[] target, double fovDeg) {
			Camera c = new Camera();
			c.eye = eye.clone();
			c.fovDeg = fovDeg;
			c.fwd = norm(sub(target, eye));
			double[] worldUp = Math.abs(c.fwd[2]) > 0.999 ? new double[] { 0, 1, 0 } : new double[] { 0, 0, 1 };
			c.right = norm(cross(c.fwd, worldUp));
			c.up = cross(c.right, c.fwd);
			return c;
		}

		/** Orthographic camera looking along {@code dir} (from the eye toward the scene). */
		public static Camera ortho(double[] dir) {
			Camera c = lookAt(new double[] { 0, 0, 0 }, dir, 0);
			c.ortho = true;
			return c;
		}

		/** View coordinates {right, up, depth (positive in front)}. */
		double[] view(double[] p) {
			double x = p[0] - eye[0], y = p[1] - eye[1], z = p[2] - eye[2];
			return new double[] { x * right[0] + y * right[1] + z * right[2], x * up[0] + y * up[1] + z * up[2],
					x * fwd[0] + y * fwd[1] + z * fwd[2] };
		}

		/** {minX, maxX, minY, maxY} of the points in view-plane coordinates (m). */
		public double[] bounds(List<double[]> pts) {
			double[] b = { Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE };
			for (double[] p : pts) {
				double[] v = view(p);
				b[0] = Math.min(b[0], v[0]);
				b[1] = Math.max(b[1], v[0]);
				b[2] = Math.min(b[2], v[1]);
				b[3] = Math.max(b[3], v[1]);
			}
			return b;
		}

		/**
		 * Sets an orthographic camera's scale (pixels per metre) and centre so {@code bounds} fill a {@code w} x
		 * {@code h} image inside the given margins.
		 */
		public void frame(double[] bounds, int w, int h, int left, int right, int top, int bottom) {
			double sx = (w - left - right) / Math.max(1e-9, bounds[1] - bounds[0]);
			double sy = (h - top - bottom) / Math.max(1e-9, bounds[3] - bounds[2]);
			orthoScale = Math.min(sx, sy);
			// The box's centre in pixels, then the view coordinates that land there.
			double px = left + (w - left - right) / 2.0, py = top + (h - top - bottom) / 2.0;
			orthoCx = (bounds[0] + bounds[1]) / 2 - (px - w / 2.0) / orthoScale;
			orthoCy = (bounds[2] + bounds[3]) / 2 + (py - h / 2.0) / orthoScale;
		}
	}

	final int w, h, ss;
	final Camera cam;
	final int[] rgb; // supersampled
	final float[] depth; // 1 / view depth (perspective) or -depth (orthographic); larger is nearer
	final double[] light;
	private final double focal; // pixels (supersampled)

	/**
	 * @param w  output width in pixels
	 * @param h  output height
	 * @param ss supersampling factor per axis (2 = 4 samples per pixel)
	 */
	public Raster3d(int w, int h, int ss, Camera cam, double[] light) {
		this.w = w;
		this.h = h;
		this.ss = ss;
		this.cam = cam;
		this.rgb = new int[w * ss * h * ss];
		this.depth = new float[rgb.length];
		Arrays.fill(depth, Float.NEGATIVE_INFINITY);
		this.light = norm(light);
		this.focal = cam.ortho ? 0 : (h * ss / 2.0) / Math.tan(Math.toRadians(cam.fovDeg / 2));
	}

	/**
	 * The supersampled colour buffer as an image, for drawing things behind every mesh (smoke, lines) with Java2D in
	 * supersampled pixels ({@link #projectSs}).
	 */
	public BufferedImage canvas() {
		int sw = w * ss, sh = h * ss;
		DataBufferInt buf = new DataBufferInt(rgb, rgb.length);
		DirectColorModel cm = new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
		WritableRaster raster = Raster.createPackedRaster(buf, sw, sh, sw, cm.getMasks(), null);
		return new BufferedImage(cm, raster, false, null);
	}

	/** As {@link #project}, in supersampled pixels. */
	public double[] projectSs(double[] p) {
		return screen(cam.view(p), ss);
	}

	/** Focal length in output pixels (perspective cameras). */
	public double focal() {
		return focal / ss;
	}

	/** Fills the background with one colour. */
	public void clear(int color) {
		Arrays.fill(rgb, color);
	}

	/** Copies a background image of the output size, scaled up to the supersampled buffer. */
	public void background(int[] img) {
		int sw = w * ss;
		for (int y = 0; y < h * ss; y++) {
			int row = (y / ss) * w;
			for (int x = 0; x < sw; x++) {
				rgb[y * sw + x] = img[row + x / ss];
			}
		}
	}

	/**
	 * Screen position (output pixels) and depth of a world point: {x, y, depth}; null behind the camera.
	 */
	public double[] project(double[] p) {
		double[] v = cam.view(p);
		return screen(v, 1);
	}

	private double[] screen(double[] v, int scale) {
		if (cam.ortho) {
			double s = cam.orthoScale * scale;
			return new double[] { w * scale / 2.0 + (v[0] - cam.orthoCx) * s, h * scale / 2.0 - (v[1] - cam.orthoCy) * s, v[2] };
		}
		if (v[2] < cam.near) {
			return null;
		}
		double f = focal * scale / ss;
		return new double[] { w * scale / 2.0 + f * v[0] / v[2], h * scale / 2.0 - f * v[1] / v[2], v[2] };
	}

	public void draw(Placed p) {
		Mesh m = p.mesh();
		float[] nrm = m.normals();
		double[] r = p.rot(), o = p.origin();
		int base = m.rgb;
		double br = (base >> 16 & 255) / 255.0, bg = (base >> 8 & 255) / 255.0, bb = (base & 255) / 255.0;
		double[][] sv = new double[3][];
		double[] shade = new double[3];
		for (int i = 0; i < m.tris.size(); i++) {
			double[] t = m.tris.get(i);
			boolean behind = false;
			for (int v = 0; v < 3; v++) {
				double lx = t[3 * v], ly = t[3 * v + 1], lz = t[3 * v + 2];
				double[] wp = { o[0] + r[0] * lx + r[1] * ly + r[2] * lz, o[1] + r[3] * lx + r[4] * ly + r[5] * lz,
						o[2] + r[6] * lx + r[7] * ly + r[8] * lz };
				double[] vv = cam.view(wp);
				sv[v] = screen(vv, ss);
				if (sv[v] == null) {
					behind = true;
					break;
				}
				if (m.emissive) {
					shade[v] = 1;
					continue;
				}
				double nx = nrm[9 * i + 3 * v], ny = nrm[9 * i + 3 * v + 1], nz = nrm[9 * i + 3 * v + 2];
				double[] wn = norm(new double[] { r[0] * nx + r[1] * ny + r[2] * nz, r[3] * nx + r[4] * ny + r[5] * nz,
						r[6] * nx + r[7] * ny + r[8] * nz }); // rot may carry a scale
				// Toward the viewer: flip back-facing normals so both sides of thin or open surfaces are lit.
				double[] toEye = cam.ortho ? new double[] { -cam.fwd[0], -cam.fwd[1], -cam.fwd[2] } : norm(sub(cam.eye, wp));
				if (dot(wn, toEye) < 0) {
					wn = new double[] { -wn[0], -wn[1], -wn[2] };
				}
				double diff = Math.max(0, dot(wn, light));
				double[] half = norm(new double[] { light[0] + toEye[0], light[1] + toEye[1], light[2] + toEye[2] });
				double spec = Math.pow(Math.max(0, dot(wn, half)), 40) * 0.35;
				shade[v] = 0.38 + 0.62 * diff + spec * 1.6; // > 1 means towards white
			}
			if (behind) {
				continue; // near-plane crossing: only the chase camera gets this close, and never to the rocket
			}
			fill(sv, shade, br, bg, bb);
		}
	}

	/** Rasterizes one triangle with per-vertex shade; depth is interpolated as 1/z (perspective-correct). */
	private void fill(double[][] s, double[] shade, double br, double bg, double bb) {
		int sw = w * ss, sh = h * ss;
		double x0 = s[0][0], y0 = s[0][1], x1 = s[1][0], y1 = s[1][1], x2 = s[2][0], y2 = s[2][1];
		double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-12) {
			return;
		}
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(sw - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(sh - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		if (minX > maxX || minY > maxY) {
			return;
		}
		double z0 = depthKey(s[0][2]), z1 = depthKey(s[1][2]), z2 = depthKey(s[2][2]);
		double inv = 1 / area;
		for (int y = minY; y <= maxY; y++) {
			double py = y + 0.5;
			for (int x = minX; x <= maxX; x++) {
				double px = x + 0.5;
				double w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
				double w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
				double w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0) {
					continue;
				}
				float z = (float) (w0 * z0 + w1 * z1 + w2 * z2);
				int idx = y * sw + x;
				if (z <= depth[idx]) {
					continue;
				}
				depth[idx] = z;
				double k = w0 * shade[0] + w1 * shade[1] + w2 * shade[2];
				rgb[idx] = shaded(br, bg, bb, k);
			}
		}
	}

	private double depthKey(double d) {
		return cam.ortho ? -d : 1 / d;
	}

	static int shaded(double r, double g, double b, double k) {
		double white = Math.max(0, k - 1);
		double f = Math.min(1, k);
		int R = (int) Math.min(255, 255 * (r * f + white));
		int G = (int) Math.min(255, 255 * (g * f + white));
		int B = (int) Math.min(255, 255 * (b * f + white));
		return R << 16 | G << 8 | B;
	}

	/** The box-filtered image at the output size. */
	public BufferedImage image() {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		int sw = w * ss, n = ss * ss;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int r = 0, g = 0, b = 0;
				for (int dy = 0; dy < ss; dy++) {
					int row = (y * ss + dy) * sw + x * ss;
					for (int dx = 0; dx < ss; dx++) {
						int c = rgb[row + dx];
						r += c >> 16 & 255;
						g += c >> 8 & 255;
						b += c & 255;
					}
				}
				out[y * w + x] = (r / n) << 16 | (g / n) << 8 | (b / n);
			}
		}
		return img;
	}

	// ------------------------------------------------------------------------------------------- vectors

	static double[] faceNormal(double[] t) {
		double ux = t[3] - t[0], uy = t[4] - t[1], uz = t[5] - t[2];
		double vx = t[6] - t[0], vy = t[7] - t[1], vz = t[8] - t[2];
		double[] n = { uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx };
		double l = Math.sqrt(dot(n, n));
		return l < 1e-18 ? new double[] { 0, 0, 1 } : new double[] { n[0] / l, n[1] / l, n[2] / l };
	}

	public static double[] sub(double[] a, double[] b) {
		return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
	}

	public static double[] add(double[] a, double[] b) {
		return new double[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] };
	}

	public static double[] scale(double[] a, double k) {
		return new double[] { a[0] * k, a[1] * k, a[2] * k };
	}

	public static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	public static double[] cross(double[] a, double[] b) {
		return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
	}

	public static double[] norm(double[] a) {
		double l = Math.sqrt(dot(a, a));
		return l < 1e-18 ? new double[] { 0, 0, 1 } : new double[] { a[0] / l, a[1] / l, a[2] / l };
	}

	/**
	 * Rotation (row-major) taking local +x to the unit vector {@code axis}, with roll {@code roll} (rad) about it.
	 */
	public static double[] alongAxis(double[] axis, double roll) {
		double[] x = norm(axis);
		double[] ref = Math.abs(x[2]) < 0.9 ? new double[] { 0, 0, 1 } : new double[] { 1, 0, 0 };
		double[] y = norm(cross(ref, x));
		double[] z = cross(x, y);
		double c = Math.cos(roll), s = Math.sin(roll);
		double[] y2 = { y[0] * c + z[0] * s, y[1] * c + z[1] * s, y[2] * c + z[2] * s };
		double[] z2 = { z[0] * c - y[0] * s, z[1] * c - y[1] * s, z[2] * c - y[2] * s };
		// Columns are the images of the local axes.
		return new double[] { x[0], y2[0], z2[0], x[1], y2[1], z2[1], x[2], y2[2], z2[2] };
	}
}
