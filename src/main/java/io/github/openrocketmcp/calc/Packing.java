package io.github.openrocketmcp.calc;

/**
 * Recovery-bay packing volume, following the flat-bar shock cord model (width x thickness x length) plus
 * vendor packing volumes, multiplied by a packing factor.
 */
public final class Packing {
	private Packing() {
	}

	public static double cordVolume(double width, double thickness, double length) {
		return width * thickness * length;
	}

	public static double cylinderVolume(double diameter, double length) {
		return Parachutes.circleArea(diameter) * length;
	}

	/**
	 * Typical packed volume of a ripstop-nylon parachute with its shroud lines, from its canopy diameter: about
	 * 0.025 in3 per square inch of D^2 (catalogue packed sizes run from ~0.013 for thin elliptical canopies to ~0.036 for
	 * heavy cruciforms). SI: V = 6.35e-4 m x D^2.
	 */
	public static double parachuteVolume(double canopyDiameter) {
		return 0.025 * 0.0254 * canopyDiameter * canopyDiameter;
	}

	/** Length of tube with inner diameter {@code innerDiameter} that holds {@code volume}. */
	public static double lengthFor(double volume, double innerDiameter) {
		return volume / Parachutes.circleArea(innerDiameter);
	}
}
