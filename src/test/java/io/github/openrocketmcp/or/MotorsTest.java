package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import info.openrocket.core.motor.ThrustCurveMotor;

class MotorsTest {

	@Test
	void impulseClassesAndCertLevels() {
		assertEquals("H", Motors.impulseClass(200));
		assertEquals("L", Motors.impulseClass(5120));
		assertEquals("M", Motors.impulseClass(5121));
		assertEquals("O", Motors.impulseClass(40000));
		assertEquals("I", Motors.certMaxClass("L1"));
		assertEquals("L", Motors.certMaxClass("level 2"));
		assertEquals(640, Motors.classMaxImpulse("I"), 1e-9);
	}

	@Test
	void customLiquidEngineMovesCgAsTanksDrain() throws Exception {
		OrRuntime.init();
		ThrustCurveMotor m = Motors.createCustom(new Motors.CustomMotor("UTAT", "CgTest", "liquid", 0.152, 2.0, 30, 12,
				new double[] { 0, 0.2, 8, 8.3 }, new double[] { 0, 4000, 3800, 0 }, 1.5, 0.6, null, null), null);
		// Loaded: (18 kg * 1.5 m + 12 kg * 0.6 m) / 30 kg = 1.14 m; empty: 1.5 m
		assertEquals(1.14, m.getLaunchCGx(), 1e-3);
		assertEquals(1.5, m.getBurnoutCGx(), 1e-3);
		assertEquals(30, m.getLaunchMass(), 1e-6);
		assertEquals(18, m.getBurnoutMass(), 1e-6);
	}
}
