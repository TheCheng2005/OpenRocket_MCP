package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.PodSet;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Designs;

/** optimize_fins picks the rocket's own fins over fins on pods, and names the choices when it cannot. */
class FinChoiceTest {
	@Test
	void theMainAirframesFinsAreChosenOverPodFins() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		TrapezoidFinSet main = null;
		BodyTube aft = null;
		for (RocketComponent c : d.doc.getRocket()) {
			if (c instanceof TrapezoidFinSet t) {
				main = t;
				aft = (BodyTube) t.getParent();
			}
		}
		PodSet pod = new PodSet();
		aft.addChild(pod);
		BodyTube podTube = new BodyTube(0.1, 0.01);
		pod.addChild(podTube);
		podTube.addChild(new TrapezoidFinSet());
		assertSame(main, FinTools.trapezoid(d, new Args(new JsonObject())));

		aft.addChild(new TrapezoidFinSet()); // a second set on the airframe itself: ask
		ToolException e = assertThrows(ToolException.class, () -> FinTools.trapezoid(d, new Args(new JsonObject())));
		assertTrue(e.getMessage().contains("choose one with finSet: ") && e.getMessage().contains(main.getName()), e.getMessage());
	}
}
