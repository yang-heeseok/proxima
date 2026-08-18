// PLANTED. This scenario is not a measurement — it exists so that `run.sh` can be watched
// doing the thing it claims to do, and `load-harness.yml` fails if it does not.
//
// A wrapper nobody has watched refuse is a claim, and this repository counts those: `R0` §4
// found nine test classes written to refuse a future edit and exactly one that had ever been
// paid. `ADR-008` moved the steady-state enforcement off a person and into `run.sh`; without
// these three it would be an assertion that it did.
export const options = { vus: 1, iterations: 1 }
export default function () {}
export function handleSummary() { return { 'steady-state.txt': 'FAIL skew=1.412\n', stdout: 'stub FAIL\n' } }
