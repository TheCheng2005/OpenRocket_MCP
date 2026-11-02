// The tours: each step points at one part of a page and says what it is for and how to use it.

import { app } from "../state.svelte";
import { analysis } from "../analysis/store.svelte";
import { keys } from "../keys";
import { ui } from "../search/ui.svelte";
import { start, type Step } from "./session.svelte";

export function designSteps(): Step[] {
  return [
    {
      title: "The Design page",
      text: "This is where the rocket is built. The parts are on the left, the rocket in the middle, the selected part's settings on the right. Every change re-flies the rocket and re-checks the rules in about a second. This tour takes a minute; use the arrow keys or the buttons.",
    },
    {
      title: "Parts",
      target: '[data-panel="design.parts"]',
      text: "Every part of the rocket in order, nose to tail, with what is inside each tube indented under it. Click one to select it; pointing at one marks it in the view. Drag a part to move it: onto a part to put it inside, between two to place it there. Right-click for duplicate, copy, paste and delete; the arrow keys walk the list.",
    },
    {
      title: "The view",
      target: '[data-panel="design.view"]',
      text: "The rocket as OpenRocket draws it. The blue CG marker is the centre of gravity at launch, the red CP marker the centre of pressure; CG must be ahead of (left of) CP. Point at a part to see its name, click to select it. Drag the selected part to slide it along the rocket: it lines up with the edges near it (Alt: freely). Drag elsewhere to move the view, scroll or pinch to zoom.",
    },
    {
      title: "Side, Back, 3D",
      target: '[data-tour="view-modes"]',
      text: "Side is OpenRocket's see-through figure, Back looks along the rocket from behind (fin layout), 3D can be turned by dragging. Exploded pulls it apart: sections along the axis, fins out, the parts inside laid out below (the slider sets how far). In 3D, Cut-away opens the airframe. Fit brings everything back into view.",
    },
    {
      title: "Pictures and drawings",
      target: '[data-tour="picture"]',
      text: "Picture makes an exploded view with a parts list, a cut-away, an outside view or a side drawing (SVG), for design reviews and build notes. The flight video is on the Flights page.",
    },
    {
      title: "Properties",
      target: '[data-panel="design.props"]',
      text: "The selected part's settings: sizes, material, finish, and for some parts a Catalogue… button for real catalogue parts. Type any unit (3 in, 75 mm), or step a number with the arrow keys. The Find box finds any setting; Position › Line up puts the part at the front, middle or back of its parent. Under Overrides, enter a weighed mass: a scale beats an estimate.",
    },
    {
      title: "Summary",
      target: '[data-panel="design.summary"]',
      text: "Stability in calibers (body diameters between CG and CP), mass, length, and a quick flight of the active configuration: apogee, top speed, acceleration and rail exit speed. It updates after every edit. Warnings about the flight show under it.",
    },
    {
      title: "Rule check",
      target: '[data-panel="design.rules"]',
      open: ["design.rules", false],
      text: "The competition's rules checked against this flight, again after every edit: FAIL must be fixed, WARN needs a look, PASS is fine. Each line names its rule. The count stays in the header when the panel is folded.",
    },
    {
      title: "Add part",
      target: '[data-panel="design.add"]',
      text: "Select a part first, then click a tile: the new part goes inside the selection if it fits there, otherwise after it. Pointing at a tile marks where it would go; a greyed tile cannot go there. Avionics bay builds a whole electronics bay between two tubes.",
    },
    {
      title: "Configuration",
      target: '[data-tour="config"]',
      text: "Which motor configuration the summary, rule check and analyses fly. Choose motors and when each parachute deploys on the Motors page.",
    },
    {
      title: "The top bar",
      target: "header",
      text: `Save (${keys("S")}), undo and redo (${keys("Z")}) every edit, Search (${keys("K")}) for anything by what it is for, the warning triangle for problems, the robot for the AI assistant, ? for the Guide, and the gear for units and colours.`,
    },
    {
      title: "That's the Design page",
      text: "Next: choose a motor, then run the analyses for a review. You can take this tour again from the Tour button on the View panel or the Guide.",
      actions: [
        { label: "Motors", run: () => (app.page = "motors") },
        {
          label: "Analysis",
          run: () => {
            analysis.selected = "start";
            app.page = "analysis";
          },
        },
      ],
    },
  ];
}

/** The Design page's tour, from anywhere: it opens the page first. */
export async function designTour() {
  ui.guide = null;
  ui.search = false;
  if (!app.design) return;
  if (app.page !== "design") {
    app.page = "design";
    // The page's code loads when first shown: wait for its panels.
    for (let i = 0; i < 50 && !document.querySelector('[data-panel="design.view"]'); i++) await new Promise((r) => setTimeout(r, 60));
  }
  start("design", designSteps());
}
