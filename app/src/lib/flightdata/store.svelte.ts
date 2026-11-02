// What the Flight data page remembers while the app is open: the log and its comparison, the library folder and scan.

export interface LogResult {
  simulation?: string;
  comparison?: { quantity: string; measured: string; simulated: string; difference: string }[];
  valuesSI?: { quantity: string; dim: string; measured: number | null; simulated: number | null }[];
  calibration?: { dragFactor?: string; use?: string };
  dragFactorFit?: number | null;
  notes?: string[];
  series?: { time: number[]; simulated: (number | null)[]; measured: (number | null)[] };
  designId: string;
  path: string;
}

export interface PastDesign {
  name: string;
  path: string;
  file: string;
  year: number;
  yearFromPath: boolean;
  error?: string;
  stages?: number;
  length?: number;
  diameter?: number;
  launchMass?: number;
  motors?: string[];
  motorClass?: string;
  totalImpulse?: number;
  predictedApogee?: number | null;
  predictionSource?: string;
  stability?: number | null;
  airframe?: string;
  fins?: string;
  recovery?: string[];
}

export interface PastFlight {
  log: string;
  path: string;
  year: number;
  apogee: number;
  design?: string;
  predictedApogee?: number | null;
}

export interface Library {
  folder: string;
  designs: PastDesign[];
  flights: PastFlight[];
  skipped: string[];
}

export const fd = $state({
  path: "",
  altitudeUnit: "",
  conditions: { windSpeed: "", launchAngle: "", launchSiteAltitude: "", temperature: "" } as Record<string, string>,
  log: null as LogResult | null,
  comparing: false,
  folder: "",
  library: null as Library | null,
  scanning: false,
});
