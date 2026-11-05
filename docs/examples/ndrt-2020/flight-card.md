# Flight card: NDRT 2020

| | |
|---|---|
| Configuration | [4895L1395-P-0] |
| Date | 2026-11-04 |
| Site / forecast | 41.78, -86.57, 206 m (675.9 ft) MSL |
| Rule set | Launch Canada 2027: DTEG Revision 4 + LC 2027 DTEG and R&R Edicts (to become DTEG R5) |

## Vehicle

| | |
|---|---|
| Length / diameter | 3391 mm (133.5 in) / 203 mm (7.992 in) |
| Launch mass | 23.32 kg (51.41 lb) |
| CG (launch) / CP | 1865 mm (73.42 in) / 2449 mm (96.41 in) from the nose tip |
| Static stability (launch / burnout) | 2.877 / 3.588 cal |
| Minimum stability in flight | 1.892 cal |

## Motors

| mount | motor | ignition | optimumDelay | configuredDelay | availableDelays | closestAvailable |
|---|---|---|---|---|---|---|
| Motor mount | 4895L1395-P | AUTOMATIC + 0.0s  | 14.29 s | 0 s | 0 s | 0 s | 

Motor ejection: with electronic deployment use the motor charge as a backup a little after apogee (optimum + 1-2 s) or plug it; never before apogee.

## Predicted flight

| | |
|---|---|
| Apogee | 1434 m (4704 ft) |
| Time to apogee | 17.69 s |
| Max velocity | 177.1 m/s (581.1 ft/s) |
| Max Mach | 0.5341 |
| Max acceleration | 67.57 m/s2 (221.7 ft/s2) = 6.891 G |
| Rail exit velocity | 19.59 m/s (64.28 ft/s) |
| Thrust-to-weight | 6.283 |
| Wind | profile (AGL) 134 m (439.6 ft): 7.36 m/s (24.15 ft/s) from 209 deg; 344 m (1129 ft): 10.87 m/s (35.66 ft/s) from 224 deg; 560 m (1837 ft): 12.43 m/s (40.78 ft/s) from 246 deg; 784 m (2572 ft): 12.3 m/s (40.35 ft/s) from 244 deg; 1013 m (3323 ft): 14.23 m/s (46.69 ft/s) from 241 deg; 1248 m (4094 ft): 16.08 m/s (52.76 ft/s) from 245 deg; 1488 m (4882 ft): 16.94 m/s (55.58 ft/s) from 254 deg; 1735 m (5692 ft): 16.51 m/s (54.17 ft/s) from 266 deg; 1989 m (6526 ft): 15.54 m/s (50.98 ft/s) from 275 deg; 2251 m (7385 ft): 15.19 m/s (49.84 ft/s) from 278 deg; 2799 m (9183 ft): 15.22 m/s (49.93 ft/s) from 273 deg |

## Recovery settings

| device | stage | event | altitude | deploysAt | descentRate |
|---|---|---|---|---|---|
| Main | Sustainer | altitude | 167.6 m (550 ft) | 158.1 m (518.8 ft) AGL, 27.81 m/s (91.24 ft/s) | 4.49 m/s (14.73 ft/s) | 
| Drogue | Sustainer | apogee |  | 1434 m (4704 ft) AGL, 25.6 m/s (83.98 ft/s) | 28.2 m/s (92.51 ft/s) | 

### Sections

| section | mass | landing energy |
|---|---|---|
| Nose cone .. Transition | 10.35 kg (22.82 lb) | 104 J (76.69 ft-lbf) (EXCEEDS 101.7 J (75 ft-lbf)) | 
| Main bay .. Switch band | 3.946 kg (8.698 lb) | 39.64 J (29.24 ft-lbf) (within 101.7 J (75 ft-lbf)) | 
| Fin can | 6.548 kg (14.44 lb) | 65.78 J (48.52 ft-lbf) (within 101.7 J (75 ft-lbf)) | 

## Electronics

| parachute | primary altimeter | backup altimeter |
|---|---|---|
| Main | 167.6 m (550 ft) AGL on the way down | 137.2 m (450 ft) AGL on the way down | 
| Drogue | apogee | apogee + 1 s | 

Mach lockout: not needed: the flight stays below Mach 0.7. Static ports: bay volume 307 in3: 4 ports of 0.219 in (5.6 mm) evenly around the switch band (same area as 3.1 x 1/4 in).

- PASS power: Primary altimeter (9 V alkaline), needs 48.33 mAh (12 mA for 4.028 h), has 440 mAh usable (550 mAh x 0.8); e-match 2.5 A
- PASS power: Backup altimeter (2S LiPo 300 mAh), needs 48.33 mAh (12 mA for 4.028 h), has 240 mAh usable (300 mAh x 0.8); e-match 3.61 A
- WARN power: GPS tracker (1S LiPo 500 mAh), needs 362.5 mAh (90 mA for 4.028 h), has 400 mAh usable (500 mAh x 0.8) (less than 30% to spare: a delay on the pad or a long search uses it up)
- PASS radio: Sustainer in flight +49.37 dB margin, landed +21.27 dB margin

## Drift vs ground wind

| wind | apogee | railExitVelocity | maxMach | minStability | maxStability | landingDistance |
|---|---|---|---|---|---|---|
| 0 m/s (0 ft/s) | 1462 m (4795 ft) | 19.6 m/s (64.29 ft/s) | 0.5311 | 2.95 cal | 3.705 cal | Sustainer 2.075 m (6.806 ft) | 
| 2.778 m/s (9.113 ft/s) | 1450 m (4759 ft) | 19.59 m/s (64.28 ft/s) | 0.5323 | 2.279 cal | 3.696 cal | Sustainer 324.9 m (1066 ft) | 
| 5.556 m/s (18.23 ft/s) | 1419 m (4656 ft) | 19.59 m/s (64.28 ft/s) | 0.5357 | 1.724 cal | 3.693 cal | Sustainer 670.2 m (2199 ft) | 
| 8.333 m/s (27.34 ft/s) | 1375 m (4511 ft) | 19.59 m/s (64.28 ft/s) | 0.5409 | 1.408 cal | 3.701 cal | Sustainer 1024 m (3359 ft) | 

## Rule check

3 failure(s), 2 warning(s).

- FAIL: Rail departure velocity (19.59 m/s (64.28 ft/s))
- FAIL: Thrust-to-weight at liftoff (avg thrust / liftoff weight) (6.399)
- FAIL: Ascent stability (minimum, rail exit to apogee while airspeed > 100 ft/s) (1.892 cal at t=0.5562 s, Mach 0.09116; angle of attack 7.773 deg there (wind / rail exit): OpenRocket's CP moves forward at high angle of attack. Zero-AoA margin: 2.939 cal)

## Sign-off

| Role | Name | Signature |
|---|---|---|
| Team lead | | |
| Recovery lead | | |
| RSO | | |

Predictions are OpenRocket simulations in the conditions above; they do not replace the RSO's review.
