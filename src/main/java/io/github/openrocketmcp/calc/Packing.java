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

	/** Length of tube with inner diameter {@code innerDiameter} that holds {@code volume}. */
	public static double lengthFor(double volume, double innerDiameter) {
		return volume / Parachutes.circleArea(innerDiameter);
	}
}
