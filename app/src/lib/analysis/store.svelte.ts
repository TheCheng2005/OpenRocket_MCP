import type { Issue } from "../api";
import type { Logged } from "../issues/issues.svelte";

// What the Analysis page remembers while the app is open: the chosen analysis, its inputs and its last result.

export interface Outcome {
  data: any;
  images: { src: string }[];
  /** Physics and output notes on the result. */
  issues: Issue[];
  ms: number;
  revision: number;
  designId: string;
}

export const analysis = $state({
  selected: "check_requirements",
  values: {} as Record<string, Record<string, string>>,
  results: {} as Record<string, Outcome>,
  /** The last run of each analysis that failed, until it runs again. */
  failures: {} as Record<string, Logged>,
});
