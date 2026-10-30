// OpenRocket's add-component buttons, in the same groups (the Design page's palette and the search use them).
export const PART_GROUPS: { title: string; parts: [string, string][] }[] = [
  { title: "Body", parts: [["NoseCone", "Nose cone"], ["BodyTube", "Body tube"], ["Transition", "Transition"]] },
  { title: "Fins", parts: [["TrapezoidFinSet", "Trapezoidal"], ["EllipticalFinSet", "Elliptical"], ["FreeformFinSet", "Freeform"], ["TubeFinSet", "Tube fins"]] },
  { title: "External", parts: [["LaunchLug", "Launch lug"], ["RailButton", "Rail button"]] },
  { title: "Inner", parts: [["InnerTube", "Inner tube"], ["TubeCoupler", "Coupler"], ["CenteringRing", "Centering ring"], ["Bulkhead", "Bulkhead"], ["EngineBlock", "Engine block"]] },
  { title: "Mass", parts: [["Parachute", "Parachute"], ["Streamer", "Streamer"], ["ShockCord", "Shock cord"], ["MassComponent", "Mass"]] },
  { title: "Assembly", parts: [["AxialStage", "Stage"], ["ParallelStage", "Boosters"], ["PodSet", "Pods"]] },
];
