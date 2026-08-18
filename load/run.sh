#!/usr/bin/env bash
#
# THE ONLY DOCUMENTED WAY TO RUN A SCENARIO IN THIS DIRECTORY, AND OPEN-8 IS WHY.
#
#   `recommendations.js` compares the two halves of its measurement window and writes OK or
#   FAIL to steady-state.txt. It cannot make k6 exit non-zero on FAIL: a k6 threshold is
#   evaluated over ONE metric, this is a ratio BETWEEN two, and it is known only once the run
#   is over -- `teardown` cannot read metric values either. So k6 prints
#
#       *** NOT STEADY STATE ... DO NOT PUBLISH THIS RUN
#
#   and exits 0 beside it. On 2026-08-17 that happened to arm B's third run in R18 and the run
#   went into a published median because nothing stopped it.
#
#   R18 fixed the check and left the enforcement as a line in load/README.md telling the
#   operator to grep the file. That is procedure, and R17 is this repository's report on what
#   becomes of a rule whose only enforcement is a person remembering it -- three failures in
#   seven days, all three caught by a human.
#
#   This script is the decision ADR-008 records. It does NOT remove the person: someone still
#   has to invoke it. What it removes is the second step -- the failure is loud at the moment
#   it happens instead of at report-writing time, which is when R18 actually found it, by
#   re-reading a log.
#
#   THAT IS WHY IT WRAPS `k6 run` RATHER THAN SITTING BESIDE IT. A separate checker would
#   create a new procedure ("did you also run the checker?") and put the problem back where it
#   started.
#
# Usage:  ./load/run.sh [scenario.js] [-- extra k6 args]
#         PROXIMA_TOKEN_SECRET must be set; recommendations.js refuses without it (R15).
set -u

here=$(cd "$(dirname "$0")" && pwd)
scenario=${1:-recommendations.js}
[ $# -gt 0 ] && shift
[ "${1:-}" = "--" ] && shift

# k6 writes handleSummary's files relative to the CURRENT WORKING DIRECTORY, not to the
# script's own directory, so the verdict lands wherever this was invoked from. Looking for it
# beside the scenario would find nothing and this wrapper would report FAIL on every correct
# run -- a guard that is always red is uninstalled within a week.
verdict="$PWD/steady-state.txt"
rm -f "$verdict"

k6 run "$here/$scenario" "$@"
k6_status=$?

# A missing verdict file is a failure, not a pass. The scenario writes it unconditionally, so
# its absence means the run did not reach handleSummary -- k6 died, the script threw, or
# somebody pointed this at a scenario that does not carry the check. Treating that as OK is
# the vacuous-gate failure this repository keeps collecting: R9 section 7 on a gate that passes
# when there is nothing to substitute, R16 on a threshold of rate>=0.0.
if [ ! -f "$verdict" ]; then
  echo ""
  echo "FAIL: no steady-state verdict was written (k6 exit $k6_status)."
  echo "The run did not reach handleSummary, or $scenario does not carry the check."
  echo "A run with no verdict is not a passing run."
  exit 2
fi

echo ""
echo "steady-state verdict: $(cat "$verdict")"

if ! grep -q '^OK' "$verdict"; then
  echo ""
  echo "FAIL: this run is NOT STEADY STATE and its numbers may not be cited."
  echo "The two halves of the measurement window disagree by more than the band in"
  echo "$scenario. Re-run once the page cache is hot, or find what changed underneath it."
  echo "See docs/reports/R18-the-pool-was-not-the-explanation.md section 3.5."
  exit 1
fi

exit $k6_status
