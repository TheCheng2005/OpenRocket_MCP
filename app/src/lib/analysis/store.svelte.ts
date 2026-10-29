// What the Analysis page remembers while the app is open: the chosen analysis, its inputs and its last result.

export interface Outcome {
  data: any;
  images: { src: string }[];
  ms: number;
  revision: number;
  designId: string;
}

export const analysis = $state({
  selected: "check_requirements",
  values: {} as Record<string, Record<string, string>>,
  results: {} as Record<string, Outcome>,
});
